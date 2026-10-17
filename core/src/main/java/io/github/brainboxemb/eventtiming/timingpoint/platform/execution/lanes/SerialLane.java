package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * One bounded FIFO execution lane that never runs two queued items concurrently.
 *
 * <p>A {@code SerialLane} owns one bounded queue and one worker runnable. Queueing
 * the first item while the lane is idle schedules that worker on the externally
 * owned executor. Each worker run processes at most {@code maxItemsPerRun} items
 * that were already queued when that run started. Work arriving during that batch
 * remains queued for the next run, so a continuously producing lane cannot keep
 * the shared executor indefinitely.</p>
 *
 * <p>The backing executor is supplied from outside this lane. Closing or failing
 * the lane never shuts down that executor.</p>
 */
public class SerialLane implements Executor, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SerialLane.class);

    /** Logical lane currently executing on this Java thread, if any. */
    private static final ThreadLocal<SerialLane> CURRENT_LANE = new ThreadLocal<SerialLane>();

    /** Lifecycle of this logical lane, independent of the backing executor lifecycle. */
    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    /** One item waiting in the lane queue. */
    private static final class QueuedWork {
        private final Runnable operation;
        private final long queuedAtNanos;

        /** Captures the operation and the time it entered the queue. */
        private QueuedWork(Runnable operation) {
            this.operation = operation;
            this.queuedAtNanos = System.nanoTime();
        }

        /** Cancels result-bearing work that will no longer run. */
        private void cancel() {
            if (operation instanceof Future<?>) {
                ((Future<?>) operation).cancel(false);
            }
        }
    }

    /**
     * FutureTask variant that exposes a fatal Error after FutureTask has captured it.
     *
     * <p>FutureTask deliberately contains task failures. The lane only needs this
     * small hook to preserve the existing rule that a fatal Error fails the lane.</p>
     */
    private static final class LaneFutureTask<R> extends FutureTask<R> {
        private Error fatalError;

        /** Creates one result-bearing queued operation. */
        private LaneFutureTask(Callable<R> operation) {
            super(operation);
        }

        /** Retains fatal Errors while preserving normal FutureTask completion semantics. */
        @Override
        protected void setException(Throwable failure) {
            if (failure instanceof Error) {
                fatalError = (Error) failure;
            }
            super.setException(failure);
        }

        /** Returns the fatal Error captured from the callable, if any. */
        private Error fatalError() {
            return fatalError;
        }
    }

    /** Diagnostic identity of this logical lane. */
    private final String laneName;

    /** Physical execution dependency supplied by Runtime composition. */
    private final Executor backingExecutor;

    /** Configured maximum number of queued lane entries. */
    private final int capacity;

    /** Bounded FIFO work queue owned by this lane. */
    private final ArrayBlockingQueue<QueuedWork> queue;

    /** Maximum number of queued items processed by one worker run. */
    private final int maxItemsPerRun;

    /** One fixed worker scheduled whenever queued work needs a lane turn. */
    private final Runnable queueWorker = this::processQueuedItems;

    private final SerialLaneMetrics metrics;

    private State state = State.NEW;
    private Throwable failure;
    private boolean queueWorkerScheduled;

    /**
     * Creates a bounded logical lane on an externally owned executor.
     *
     * @param capacity maximum number of items waiting in the lane queue
     * @param maxItemsPerRun maximum number of queued items processed by one worker run
     */
    public SerialLane(int capacity, int maxItemsPerRun, String laneName, Executor backingExecutor) {
        failIfInvalidArgument(capacity >= 1, "capacity must be positive");
        failIfInvalidArgument(maxItemsPerRun >= 1, "maxItemsPerRun must be positive");
        failIfInvalidArgument(laneName != null && !laneName.trim().isEmpty(), "laneName must not be blank");
        failIfInvalidArgument(backingExecutor != null, "backingExecutor must not be null");

        this.laneName = laneName.trim();
        this.backingExecutor = backingExecutor;
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<QueuedWork>(capacity);
        this.maxItemsPerRun = maxItemsPerRun;
        this.metrics = new SerialLaneMetrics(queue::size);
    }

    /** Starts or restarts this logical lane. */
    public synchronized void start() {
        failIfIllegalState(state == State.NEW || state == State.STOPPED, "SerialLane cannot start from %s", state);
        state = State.RUNNING;
    }

    /**
     * Queues fire-and-forget work for serial execution.
     *
     * <p>The call returns after queueing and throws {@link RejectedExecutionException}
     * when the lane cannot accept the work.</p>
     */
    @Override
    public void execute(Runnable work) {
        failIfInvalidArgument(work != null, "work must not be null");
        queue(work);
    }

    /**
     * Queues result-bearing work and returns its standard Java {@link Future}.
     */
    public <R> Future<R> submit(Callable<R> work) {
        failIfInvalidArgument(work != null, "work must not be null");

        LaneFutureTask<R> future = new LaneFutureTask<R>(work);
        queue(future);
        return future;
    }

    /**
     * Adds one operation to the bounded FIFO queue and schedules the queue worker
     * when this queue transitions from idle to runnable.
     */
    private void queue(Runnable operation) {
        boolean scheduleQueueWorker = false;

        synchronized (this) {
            ensureRunning();

            QueuedWork queuedWork = new QueuedWork(operation);
            if (!queue.offer(queuedWork)) {
                metrics.recordFull();
                throw new RejectedExecutionException("Serial lane " + laneName + " queue is full");
            }

            metrics.recordQueued(queue.size());

            if (!queueWorkerScheduled) {
                queueWorkerScheduled = true;
                scheduleQueueWorker = true;
            }
        }

        if (scheduleQueueWorker) {
            scheduleQueueWorker();
        }
    }

    /** Throws the standard rejection exception when this lane is not accepting work. */
    private void ensureRunning() {
        if (state != State.RUNNING) {
            metrics.recordNotRunning();
            throw new RejectedExecutionException("Serial lane " + laneName + " is not running");
        }
    }

    /**
     * Schedules the one fixed queue worker on the externally owned executor.
     *
     * <p>A rejected worker schedule is a terminal lane failure because already
     * queued work can no longer make progress.</p>
     */
    private void scheduleQueueWorker() {
        try {
            backingExecutor.execute(queueWorker);
        } catch (RejectedExecutionException ex) {
            failLane(ex);
            throw ex;
        }
    }

    /**
     * Processes up to maxItemsPerRun items that were queued when this worker run started.
     *
     * <p>New work may be queued while this batch runs. Those later items remain
     * in the queue and cause another worker run after this batch completes.</p>
     */
    private void processQueuedItems() {
        final int itemCount;

        synchronized (this) {
            if (state == State.FAILED || queue.isEmpty()) {
                queueWorkerScheduled = false;
                notifyAll();
                return;
            }
            itemCount = Math.min(queue.size(), maxItemsPerRun);
        }

        SerialLane previousLane = CURRENT_LANE.get();
        CURRENT_LANE.set(this);

        try {
            for (int index = 0; index < itemCount; index++) {
                QueuedWork work = queue.poll();
                if (work == null) {
                    break;
                }

                long startedAtNanos = System.nanoTime();
                try {
                    processQueuedItem(work.operation);
                } finally {
                    long finishedAtNanos = System.nanoTime();
                    metrics.recordCompleted(
                            elapsedNanos(work.queuedAtNanos, startedAtNanos),
                            elapsedNanos(startedAtNanos, finishedAtNanos));
                }
            }
        } finally {
            restoreCurrentLane(previousLane);

            boolean scheduleNextBatch = false;
            synchronized (this) {
                if (state == State.FAILED) {
                    cancelQueuedWork();
                    queueWorkerScheduled = false;
                    notifyAll();
                } else if (queue.isEmpty()) {
                    queueWorkerScheduled = false;
                    notifyAll();
                } else {
                    scheduleNextBatch = true;
                }
            }

            if (scheduleNextBatch) {
                scheduleQueueWorker();
            }
        }
    }

    /** Processes one queue item while applying only lane-level failure handling. */
    private void processQueuedItem(Runnable operation) {
        try {
            operation.run();

            if (operation instanceof LaneFutureTask<?>) {
                Error fatalError = ((LaneFutureTask<?>) operation).fatalError();
                if (fatalError != null) {
                    failLane(fatalError);
                }
            }
        } catch (RuntimeException ex) {
            LOG.warn("Serial lane {} task failed", laneName, ex);
        } catch (Error ex) {
            failLane(ex);
            throw ex;
        }
    }

    /** Restores the previously active logical lane for this Java thread. */
    private static void restoreCurrentLane(SerialLane previousLane) {
        if (previousLane == null) {
            CURRENT_LANE.remove();
        } else {
            CURRENT_LANE.set(previousLane);
        }
    }

    /** Configured queue capacity used by typed item queues on this lane. */
    int capacity() {
        return capacity;
    }

    /** Configured worker batch size used by typed item queues on this lane. */
    int maxItemsPerRun() {
        return maxItemsPerRun;
    }

    /** Returns whether this lane is currently accepting normal work. */
    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    /** Returns the current logical lane lifecycle state for diagnostics and detailed inspection. */
    public synchronized State state() {
        return state;
    }

    /** Returns the first fatal lane/executor failure, or {@code null} when healthy. */
    public synchronized Throwable failure() {
        return failure;
    }

    /** True only while this Java thread is executing work for this logical lane. */
    public boolean isExecutingOnCurrentThread() {
        return CURRENT_LANE.get() == this;
    }

    /** Returns the separate pull-based metrics owner for this lane. */
    public SerialLaneMetrics metrics() {
        return metrics;
    }

    /**
     * Stops accepting new work and waits for already queued/running work to finish.
     */
    @Override
    public synchronized void close() {
        if (state == State.NEW) {
            state = State.STOPPED;
        } else {
            if (state == State.RUNNING) {
                state = State.STOPPING;
            }

            if (state != State.STOPPED && state != State.FAILED) {
                boolean interrupted = false;
                while (queueWorkerScheduled || !queue.isEmpty()) {
                    try {
                        wait(100L);
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }

                state = State.STOPPED;
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Marks the lane failed and cancels work that can no longer execute. */
    private synchronized void failLane(Throwable cause) {
        if (state != State.FAILED) {
            failure = cause;
            state = State.FAILED;
            cancelQueuedWork();
            queueWorkerScheduled = false;
            notifyAll();
        }
    }

    /** Cancels and removes every item still waiting in the private queue. */
    private void cancelQueuedWork() {
        QueuedWork queuedWork;
        while ((queuedWork = queue.poll()) != null) {
            queuedWork.cancel();
        }
    }

    /** Returns a non-negative elapsed duration for lane metrics. */
    private static long elapsedNanos(long startedNanos, long finishedNanos) {
        long elapsed = finishedNanos - startedNanos;
        return elapsed < 0L ? 0L : elapsed;
    }
}
