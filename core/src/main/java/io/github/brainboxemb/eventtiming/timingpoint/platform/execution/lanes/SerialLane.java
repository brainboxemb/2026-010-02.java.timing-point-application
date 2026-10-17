package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * One bounded FIFO item lane serviced by one fixed worker.
 *
 * <p>The queue contains caller-owned items of type {@code T}. The backing
 * executor only sees this lane's one fixed worker runnable. Queueing another item
 * while that worker is scheduled or running never schedules a second worker for
 * the same lane.</p>
 *
 * <p>Each worker run takes at most {@code maxItemsPerRun} items that were already
 * queued when the run started. Items arriving during that batch stay queued for
 * the next worker run.</p>
 *
 * @param <T> queued item type
 */
public final class SerialLane<T> implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SerialLane.class);

    /** Logical item lane currently executing on this Java thread, if any. */
    private static final ThreadLocal<SerialLane<?>> CURRENT_LANE =
            new ThreadLocal<SerialLane<?>>();

    /** Lifecycle of this logical lane, independent of the backing executor lifecycle. */
    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    /** Internal measurement envelope around one caller-owned item. */
    private static final class QueuedItem<T> {
        private final T item;
        private final long queuedAtNanos;

        private QueuedItem(T item) {
            this.item = item;
            this.queuedAtNanos = System.nanoTime();
        }
    }

    private final String laneName;
    private final Executor backingExecutor;
    private final ArrayBlockingQueue<QueuedItem<T>> queue;
    private final int maxItemsPerRun;
    private final Consumer<T> itemProcessor;
    private final Runnable queueWorker = this::runQueueWorker;
    private final SerialLaneMetrics metrics;

    private State state = State.NEW;
    private Throwable failure;
    private boolean queueWorkerScheduled;

    /**
     * Creates one typed logical lane on an externally owned executor.
     *
     * @param capacity maximum number of caller-owned items waiting in the queue
     * @param maxItemsPerRun maximum number of items handled by one worker run
     * @param laneName diagnostic lane name
     * @param backingExecutor physical execution dependency
     * @param itemProcessor caller-owned processor for one queued item
     */
    public SerialLane(
            int capacity,
            int maxItemsPerRun,
            String laneName,
            Executor backingExecutor,
            Consumer<T> itemProcessor) {

        failIfInvalidArgument(capacity >= 1, "capacity must be positive");
        failIfInvalidArgument(maxItemsPerRun >= 1, "maxItemsPerRun must be positive");
        failIfInvalidArgument(laneName != null && !laneName.trim().isEmpty(), "laneName must not be blank");
        failIfInvalidArgument(backingExecutor != null, "backingExecutor must not be null");
        failIfInvalidArgument(itemProcessor != null, "itemProcessor must not be null");

        this.laneName = laneName.trim();
        this.backingExecutor = backingExecutor;
        this.queue = new ArrayBlockingQueue<QueuedItem<T>>(capacity);
        this.maxItemsPerRun = maxItemsPerRun;
        this.itemProcessor = itemProcessor;
        this.metrics = new SerialLaneMetrics(queue::size);
    }

    /** Starts or restarts this logical lane. */
    public synchronized void start() {
        failIfIllegalState(
                state == State.NEW || state == State.STOPPED,
                "SerialLane cannot start from %s",
                state);
        state = State.RUNNING;
    }

    /**
     * Attempts to queue one caller-owned item without waiting for capacity.
     */
    public OfferResult offer(T item) {
        failIfInvalidArgument(item != null, "item must not be null");

        boolean scheduleWorker = false;
        synchronized (this) {
            if (state != State.RUNNING) {
                metrics.recordNotRunning();
                return OfferResult.NOT_RUNNING;
            }

            if (!queue.offer(new QueuedItem<T>(item))) {
                metrics.recordFull();
                return OfferResult.FULL;
            }

            metrics.recordQueued(queue.size());
            if (!queueWorkerScheduled) {
                queueWorkerScheduled = true;
                scheduleWorker = true;
            }
        }

        if (scheduleWorker) {
            scheduleQueueWorker();
        }
        return OfferResult.ACCEPTED;
    }

    /** Immediate result of attempting to queue one item. */
    public enum OfferResult {
        ACCEPTED,
        FULL,
        NOT_RUNNING
    }

    /** Schedules the one fixed queue worker on the externally owned executor. */
    private void scheduleQueueWorker() {
        try {
            backingExecutor.execute(queueWorker);
        } catch (RejectedExecutionException ex) {
            failLane(ex);
            throw ex;
        }
    }

    /** Runs one bounded batch of items already waiting when this worker run starts. */
    private void runQueueWorker() {
        final int itemCount;

        synchronized (this) {
            if (state == State.FAILED || queue.isEmpty()) {
                queueWorkerScheduled = false;
                notifyAll();
                return;
            }
            itemCount = Math.min(queue.size(), maxItemsPerRun);
        }

        SerialLane<?> previousLane = CURRENT_LANE.get();
        CURRENT_LANE.set(this);

        try {
            for (int index = 0; index < itemCount; index++) {
                QueuedItem<T> queuedItem = queue.poll();
                if (queuedItem == null) {
                    break;
                }

                long startedAtNanos = System.nanoTime();
                try {
                    runQueuedItem(queuedItem.item);
                } finally {
                    long finishedAtNanos = System.nanoTime();
                    metrics.recordCompleted(
                            elapsedNanos(queuedItem.queuedAtNanos, startedAtNanos),
                            elapsedNanos(startedAtNanos, finishedAtNanos));
                }
            }
        } finally {
            restoreCurrentLane(previousLane);

            boolean scheduleNextBatch = false;
            synchronized (this) {
                if (state == State.FAILED) {
                    queue.clear();
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

    /** Runs caller-owned processing for one item while keeping lane failure semantics local. */
    private void runQueuedItem(T item) {
        try {
            itemProcessor.accept(item);
        } catch (RuntimeException ex) {
            LOG.warn("Serial lane {} item processing failed", laneName, ex);
        } catch (Error error) {
            failLane(error);
            throw error;
        }
    }

    private static void restoreCurrentLane(SerialLane<?> previousLane) {
        if (previousLane == null) {
            CURRENT_LANE.remove();
        } else {
            CURRENT_LANE.set(previousLane);
        }
    }

    /** Returns whether this lane is currently accepting items. */
    public synchronized boolean isRunning() {
        return state == State.RUNNING;
    }

    /** Returns the current logical lane lifecycle state for diagnostics. */
    public synchronized State state() {
        return state;
    }

    /** Returns the first fatal lane/executor failure, or {@code null} when healthy. */
    public synchronized Throwable failure() {
        return failure;
    }

    /** True only while this Java thread is executing this lane's item processor. */
    public boolean isExecutingOnCurrentThread() {
        return CURRENT_LANE.get() == this;
    }

    /** Returns item-queue metrics for this lane. */
    public SerialLaneMetrics metrics() {
        return metrics;
    }

    /** Stops accepting items and waits for already accepted items to finish. */
    @Override
    public synchronized void close() {
        if (state == State.NEW) {
            state = State.STOPPED;
            return;
        }

        if (state == State.RUNNING) {
            state = State.STOPPING;
        }

        if (state == State.STOPPED || state == State.FAILED) {
            return;
        }

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

    /** Marks the lane terminally failed and drops items that can no longer progress. */
    private synchronized void failLane(Throwable cause) {
        if (state != State.FAILED) {
            failure = cause;
            state = State.FAILED;
            queue.clear();
            queueWorkerScheduled = false;
            notifyAll();
        }
    }

    private static long elapsedNanos(long startedNanos, long finishedNanos) {
        long elapsed = finishedNanos - startedNanos;
        return elapsed < 0L ? 0L : elapsed;
    }
}
