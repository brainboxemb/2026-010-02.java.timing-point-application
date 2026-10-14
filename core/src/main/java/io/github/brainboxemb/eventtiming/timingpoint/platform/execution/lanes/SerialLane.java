package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bounded FIFO execution lane that never runs two of its tasks concurrently.
 *
 * <p>A {@code SerialLane} is a logical lane, not necessarily a Java thread.
 * Production runtime composition gives multiple lanes one shared role worker.
 * Each lane still owns its own bounded queue, admission result, FIFO ordering
 * and lifecycle. The shared worker only executes short drain tokens submitted by
 * those lanes.</p>
 *
 * <p>The physical worker is supplied from outside this lane. The lane uses it
 * only to execute drain tokens; it never creates, configures or shuts down the
 * underlying worker. Runtime composition therefore owns physical worker
 * lifecycle while this class owns only lane-local execution behaviour.</p>
 */
public final class SerialLane implements Executor, AutoCloseable {
    private static final Logger LOG =
            LoggerFactory.getLogger(SerialLane.class);

    /**
     * Logical lane currently executing on this Java thread, if any.
     *
     * <p>Used only to detect blocking same-lane reentrancy. The previous value
     * is restored for nested execution of another logical lane.</p>
     */
    private static final ThreadLocal<SerialLane> CURRENT_LANE =
            new ThreadLocal<SerialLane>();

    /** Lifecycle of this logical lane, independent of any shared worker lifecycle. */
    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    /**
     * Internal queue entry that adds timing/cancellation hooks around Runnable/FutureTask.
     */
    private interface TrackedTask extends Runnable {
        void markAccepted(long acceptedAtNanos);

        void cancelIfFuture();
    }

    /** Diagnostic identity of this logical lane; not a thread name in shared mode. */
    private final String laneName;

    /**
     * Physical execution dependency supplied by the composition owner.
     *
     * <p>This lane may submit drain tokens but never owns this executor's
     * lifecycle.</p>
     */
    private final Executor workerExecutor;

    /** Bounded workload queue owned by this lane. */
    private final ArrayBlockingQueue<TrackedTask> queue;
    private final SerialLaneMetrics metrics;

    private State state = State.NEW;

    /** First fatal lane/executor failure, if any. */
    private Throwable failure;

    /**
     * True while this lane has a drain token running or waiting on the worker.
     * At most one drain token per lane is scheduled at a time.
     */
    private boolean drainScheduled;

    /** True only while one queue item from this lane is executing. */
    private boolean taskRunning;

    /**
     * Creates a bounded logical lane on an externally owned worker.
     *
     * <p>The supplied executor is a dependency only. Closing or faulting this
     * lane never shuts it down.</p>
     */
    public SerialLane(
            int capacity,
            String laneName,
            Executor workerExecutor) {
        validateCapacity(capacity);
        if (workerExecutor == null) {
            throw new IllegalArgumentException(
                    "workerExecutor must not be null");
        }

        this.laneName = requireLaneName(laneName);
        this.workerExecutor = workerExecutor;
        this.queue = new ArrayBlockingQueue<TrackedTask>(capacity);
        this.metrics = new SerialLaneMetrics(queue::size);
    }

    /** Rejects an invalid bounded queue capacity during construction. */
    private static void validateCapacity(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "capacity must be positive");
        }
    }

    /** Validates and normalizes the diagnostic lane name. */
    private static String requireLaneName(String laneName) {
        if (laneName == null || laneName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "laneName must not be blank");
        }
        return laneName.trim();
    }

    /** Starts or restarts this logical lane on the externally owned worker. */
    public synchronized void start() {
        if (state != State.NEW && state != State.STOPPED) {
            throw new IllegalStateException("SerialLane cannot start from " + state);
        }
        state = State.RUNNING;
    }

    /**
     * Queues fire-and-forget work for serial execution.
     *
     * <p>This follows {@link Executor#execute(Runnable)} semantics: the call
     * returns after queueing and throws {@link RejectedExecutionException} when
     * the lane cannot accept the work. It does not report the later task result.</p>
     */
    @Override
    public void execute(Runnable work) {
        if (work == null) {
            throw new IllegalArgumentException("work must not be null");
        }
        enqueue(new TrackedRunnable(work));
    }

    /**
     * Queues result-bearing work and returns the standard Java {@link Future}.
     *
     * <p>The method does not wait for execution. Queue rejection is reported
     * immediately with {@link RejectedExecutionException}.</p>
     */
    public <R> Future<R> submit(Callable<R> work) {
        if (work == null) {
            throw new IllegalArgumentException("work must not be null");
        }

        TrackedFutureTask<R> task = new TrackedFutureTask<R>(work);
        enqueue(task);
        return task;
    }

    /**
     * Adds one tracked task to the bounded lane queue or rejects it immediately.
     *
     * <p>Queue implementation details stay inside SerialLane; callers only see
     * the standard execute/submit and rejection model.</p>
     */
    private void enqueue(TrackedTask task) {
        synchronized (this) {
            if (state != State.RUNNING) {
                metrics.recordNotRunning();
                throw new RejectedExecutionException("Serial lane " + laneName + " is not running");
            }

            task.markAccepted(System.nanoTime());
            if (!enqueueTaskLocked(task)) {
                metrics.recordFull();
                throw new RejectedExecutionException("Serial lane " + laneName + " queue is full");
            }

            if (!drainScheduled) {
                drainScheduled = true;
                scheduleNextExecutionLocked();
            }

            metrics.recordAccepted(queue.size());
        }
    }

    /** Adds one task to the bounded internal queue without waiting for capacity. */
    private boolean enqueueTaskLocked(TrackedTask task) {
        return queue.offer(task);
    }

    /**
     * Asks the externally owned Executor to run the next SerialLane turn.
     *
     * <p>If the worker rejects the turn, this lane becomes FAILED and all work
     * still waiting in its private queue is cancelled.</p>
     */
    private void scheduleNextExecutionLocked() {
        try {
            workerExecutor.execute(this::executeNextTask);
        } catch (RejectedExecutionException ex) {
            failure = ex;
            state = State.FAILED;
            drainScheduled = false;
            cancelQueuedLocked();
            notifyAll();
            throw ex;
        }
    }

    /**
     * Executes one queued lane item and schedules another turn when work remains.
     *
     * <p>Processing one item per worker turn is intentional: on a shared
     * one-worker Executor, a sibling lane already waiting can run before this
     * lane schedules its next item.</p>
     */
    private void executeNextTask() {
        final TrackedTask task;
        synchronized (this) {
            if (state == State.FAILED) {
                drainScheduled = false;
                notifyAll();
                return;
            }

            task = nextQueuedTaskLocked();
            if (task == null) {
                drainScheduled = false;
                notifyAll();
                return;
            }
            taskRunning = true;
        }

        SerialLane previousLane = CURRENT_LANE.get();
        CURRENT_LANE.set(this);
        try {
            task.run();
        } finally {
            if (previousLane == null) {
                CURRENT_LANE.remove();
            } else {
                CURRENT_LANE.set(previousLane);
            }

            synchronized (this) {
                taskRunning = false;

                if (state == State.FAILED) {
                    cancelQueuedLocked();
                    drainScheduled = false;
                    notifyAll();
                    return;
                }

                if (queue.isEmpty()) {
                    drainScheduled = false;
                    notifyAll();
                    return;
                }

                scheduleNextExecutionLocked();
            }
        }
    }

    /** Removes and returns the next queued task, or {@code null} when none remains. */
    private TrackedTask nextQueuedTaskLocked() {
        return queue.poll();
    }

    /** Returns the current logical lane lifecycle state. */
    public synchronized State state() {
        return state;
    }

    /** Returns the first fatal lane/worker failure, or {@code null} when healthy. */
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
     * Stops new admission and waits for work already accepted by this lane.
     *
     * <p>Closing the lane never shuts down its externally owned worker.</p>
     */
    @Override
    public synchronized void close() {
        if (state == State.NEW) {
            state = State.STOPPED;
            return;
        }
        if (state == State.RUNNING) {
            state = State.STOPPING;
        }
        if (state == State.STOPPED) {
            return;
        }

        if (state != State.FAILED) {
            boolean interrupted = false;
            while (drainScheduled
                    || taskRunning
                    || !queue.isEmpty()) {
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

    /** Marks a fatal task error as the terminal failure of this logical lane. */
    private synchronized void markFailed(Error cause) {
        if (state == State.FAILED) {
            return;
        }
        failure = cause;
        state = State.FAILED;
        cancelQueuedLocked();
        drainScheduled = false;
        notifyAll();
    }

    /** Cancels all work still waiting in the private lane queue. */
    private void cancelQueuedLocked() {
        TrackedTask queued;
        while ((queued = nextQueuedTaskLocked()) != null) {
            queued.cancelIfFuture();
        }
    }

    /** Returns a non-negative elapsed duration for lane metrics. */
    private static long elapsedNanos(
            long startedNanos,
            long finishedNanos) {
        long elapsed = finishedNanos - startedNanos;
        return elapsed < 0L ? 0L : elapsed;
    }

    private final class TrackedRunnable
            implements TrackedTask {
        private final Runnable delegate;
        private long acceptedAtNanos;

        private TrackedRunnable(Runnable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void markAccepted(long acceptedAtNanos) {
            this.acceptedAtNanos = acceptedAtNanos;
        }

        @Override
        public void cancelIfFuture() {
            // Fire-and-forget work has no Future to cancel.
        }

        @Override
        public void run() {
            long startedNanos = System.nanoTime();
            try {
                delegate.run();
            } catch (RuntimeException ex) {
                LOG.warn(
                        "Serial lane {} task failed",
                        laneName,
                        ex);
            } catch (Error ex) {
                markFailed(ex);
                throw ex;
            } finally {
                metrics.recordCompleted(
                        elapsedNanos(
                                acceptedAtNanos,
                                startedNanos),
                        elapsedNanos(
                                startedNanos,
                                System.nanoTime()));
            }
        }
    }

    private final class TrackedFutureTask<R>
            extends FutureTask<R>
            implements TrackedTask {
        private final FatalTrackingCallable<R> trackedCallable;
        private long acceptedAtNanos;

        private TrackedFutureTask(Callable<R> work) {
            this(new FatalTrackingCallable<R>(work));
        }

        private TrackedFutureTask(
                FatalTrackingCallable<R> trackedCallable) {
            super(trackedCallable);
            this.trackedCallable = trackedCallable;
        }

        @Override
        public void markAccepted(long acceptedAtNanos) {
            this.acceptedAtNanos = acceptedAtNanos;
        }

        @Override
        public void cancelIfFuture() {
            cancel(false);
        }

        @Override
        public void run() {
            long startedNanos = System.nanoTime();
            try {
                super.run();
            } finally {
                metrics.recordCompleted(
                        elapsedNanos(
                                acceptedAtNanos,
                                startedNanos),
                        elapsedNanos(
                                startedNanos,
                                System.nanoTime()));
                Error fatal = trackedCallable.fatalError();
                if (fatal != null) {
                    markFailed(fatal);
                }
            }
        }
    }

    private static final class FatalTrackingCallable<R>
            implements Callable<R> {
        private final Callable<R> delegate;
        private volatile Error fatalError;

        private FatalTrackingCallable(Callable<R> delegate) {
            this.delegate = delegate;
        }

        @Override
        public R call() throws Exception {
            try {
                return delegate.call();
            } catch (Error ex) {
                fatalError = ex;
                throw ex;
            }
        }

        private Error fatalError() {
            return fatalError;
        }
    }
}
