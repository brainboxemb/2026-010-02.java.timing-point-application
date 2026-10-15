package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * One bounded FIFO execution lane that never runs two of its tasks concurrently.
 *
 * <p>A {@code SerialLane} is a logical lane, not necessarily a Java thread.
 * Production runtime composition gives multiple lanes one shared backing executor.
 * Each lane still owns its own bounded queue, FIFO ordering and lifecycle.
 * Callers use the standard Java execute/submit/rejection model; the backing executor
 * only runs short lane turns that execute one queued task at a time.</p>
 *
 * <p>The backing executor is supplied from outside this lane. The lane uses it
 * only to execute queued lane turns; it never creates, configures or shuts down the
 * underlying executor. Runtime composition therefore owns executor
 * lifecycle while this class owns only lane-local execution behaviour.</p>
 */
public class SerialLane implements Executor, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SerialLane.class);

    /**
     * Logical lane currently executing on this Java thread, if any.
     *
     * <p>Used only to detect blocking same-lane reentrancy. The previous value
     * is restored for nested execution of another logical lane.</p>
     */
    private static final ThreadLocal<SerialLane> CURRENT_LANE = new ThreadLocal<SerialLane>();

    /** Lifecycle of this logical lane, independent of any backing-executor lifecycle. */
    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    /**
     * Internal executable work item with separate timing and cancellation support.
     */
    private interface TrackedTask extends Runnable {
        /** Returns the timing state used only for per-task lane metrics. */
        TaskTiming timing();

        /** Cancels work that exposes a Future; fire-and-forget work is a no-op. */
        void cancelQueuedTask();
    }

    /**
     * Per-task timestamps used to derive queue wait and execution durations.
     *
     * <p>This object deliberately owns only measurement state. Task execution,
     * queueing and lane lifecycle remain outside it.</p>
     */
    private static final class TaskTiming {
        private long queuedAtNanos;
        private long startedAtNanos;
        private long finishedAtNanos;

        /** Records when the task entered the SerialLane queue. */
        private void recordQueuedAt(long queuedAtNanos) {
            this.queuedAtNanos = queuedAtNanos;
        }

        /** Records when the task actually started executing. */
        private void recordStartedAt(long startedAtNanos) {
            this.startedAtNanos = startedAtNanos;
        }

        /** Records when task execution finished. */
        private void recordFinishedAt(long finishedAtNanos) {
            this.finishedAtNanos = finishedAtNanos;
        }

        /** Returns how long the task waited in the SerialLane queue. */
        private long queueWaitNanos() {
            return elapsedNanos(
                    queuedAtNanos,
                    startedAtNanos);
        }

        /** Returns how long the task itself executed. */
        private long executionNanos() {
            return elapsedNanos(
                    startedAtNanos,
                    finishedAtNanos);
        }
    }

    /** Diagnostic identity of this logical lane; not a thread name in shared mode. */
    private final String laneName;

    /**
     * Physical execution dependency supplied by the composition owner.
     *
     * <p>This lane may submit execution turns but never owns this executor's
     * lifecycle.</p>
     */
    private final Executor backingExecutor;

    /** Bounded workload queue owned by this lane. */
    private final ArrayBlockingQueue<TrackedTask> queue;
    private final SerialLaneMetrics metrics;

    private State state = State.NEW;

    /** First fatal lane/executor failure, if any. */
    private Throwable failure;

    /**
     * True while this lane has a queued execution turn running or waiting on the backing executor.
     * At most one execution turn per lane is scheduled at a time.
     */
    private boolean executionScheduled;

    /** True only while one queue item from this lane is executing. */
    private boolean taskRunning;

    /**
     * Creates a bounded logical lane on an externally owned executor.
     *
     * <p>The supplied executor is a dependency only. Closing or faulting this
     * lane never shuts it down.</p>
     */
    public SerialLane(int capacity, String laneName, Executor backingExecutor) {
        checkArgument(capacity >= 1, "capacity must be positive");
        checkArgument(laneName != null && !laneName.trim().isEmpty(), "laneName must not be blank");
        checkArgument(backingExecutor != null, "backingExecutor must not be null");

        this.laneName = laneName.trim();
        this.backingExecutor = backingExecutor;
        this.queue = new ArrayBlockingQueue<TrackedTask>(capacity);
        this.metrics = new SerialLaneMetrics(queue::size);
    }

    /** Starts or restarts this logical lane on the externally owned executor. */
    public synchronized void start() {
        checkState(state == State.NEW || state == State.STOPPED, "SerialLane cannot start from %s", state);
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
        checkArgument(work != null, "work must not be null");
        queueTask(new TrackedRunnable(work));
    }

    /**
     * Queues result-bearing work and returns the standard Java {@link Future}.
     *
     * <p>The method does not wait for execution. Queue rejection is reported
     * immediately with {@link RejectedExecutionException}.</p>
     */
    public <R> Future<R> submit(Callable<R> work) {
        checkArgument(work != null, "work must not be null");

        TrackedFutureTask<R> task = new TrackedFutureTask<R>(work);
        queueTask(task);
        return task;
    }

    /**
     * Queues one tracked task or throws when this lane cannot accept it.
     *
     * <p>Queue implementation details stay inside SerialLane; callers only see
     * the standard execute/submit and rejection model.</p>
     */
    private void queueTask(TrackedTask task) {
        synchronized (this) {
            ensureRunning();

            task.timing().recordQueuedAt(
                    System.nanoTime());
            addTaskToQueue(task);

            if (!executionScheduled) {
                executionScheduled = true;
                scheduleNextExecution();
            }

            metrics.recordQueued(
                    queue.size());
        }
    }

    /** Throws the standard rejection exception when this lane is not accepting work. */
    private void ensureRunning() {
        if (state != State.RUNNING) {
            metrics.recordNotRunning();
            throw new RejectedExecutionException(
                    "Serial lane " + laneName + " is not running");
        }
    }

    /** Adds one task to the bounded queue or throws when no queue capacity remains. */
    private void addTaskToQueue(TrackedTask task) {
        if (!queue.offer(task)) {
            metrics.recordFull();
            throw new RejectedExecutionException(
                    "Serial lane " + laneName + " queue is full");
        }
    }

    /**
     * Asks the externally owned Executor to run the next SerialLane turn.
     *
     * <p>The caller already owns this lane's monitor. If the backing executor rejects the
     * turn, this lane becomes FAILED and all queued work is cancelled.</p>
     */
    private void scheduleNextExecution() {
        try {
            backingExecutor.execute(this::executeNextTask);
        } catch (RejectedExecutionException ex) {
            failure = ex;
            state = State.FAILED;
            executionScheduled = false;
            cancelQueuedTasks();
            notifyAll();
            throw ex;
        }
    }

    /**
     * Executes one queued lane item and schedules another turn when work remains.
     *
     * <p>Processing one item per executor turn is intentional: on a shared
     * single-thread Executor, a sibling lane already waiting can run before this
     * lane schedules its next item.</p>
     */
    private void executeNextTask() {
        final TrackedTask task;
        synchronized (this) {
            if (state == State.FAILED) {
                executionScheduled = false;
                notifyAll();
                return;
            }

            task = removeNextTaskFromQueue();
            if (task == null) {
                executionScheduled = false;
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
                    cancelQueuedTasks();
                    executionScheduled = false;
                    notifyAll();
                    return;
                }

                if (queue.isEmpty()) {
                    executionScheduled = false;
                    notifyAll();
                    return;
                }

                scheduleNextExecution();
            }
        }
    }

    /**
     * Removes and returns the next queued task, or {@code null} when none remains.
     *
     * <p>The caller owns this lane's monitor while changing queue/execution state.</p>
     */
    private TrackedTask removeNextTaskFromQueue() {
        return queue.poll();
    }

    /** Returns the current logical lane lifecycle state. */
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
     * Stops accepting new work and waits for queued/running work to finish.
     *
     * <p>Closing the lane never shuts down its externally owned executor.</p>
     */
    @Override
    public synchronized void close() {
        if (state == State.NEW) {
            state = State.STOPPED;
        } else {
            if (state == State.RUNNING) {
                state = State.STOPPING;
            }

            if (state != State.STOPPED
                    && state != State.FAILED) {
                boolean interrupted = false;
                while (executionScheduled
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
    }

    /** Marks a fatal task error as the terminal failure of this logical lane. */
    private synchronized void markFailed(Error cause) {
        if (state == State.FAILED) {
            return;
        }
        failure = cause;
        state = State.FAILED;
        cancelQueuedTasks();
        executionScheduled = false;
        notifyAll();
    }

    /** Cancels all work still waiting in the private lane queue. */
    private void cancelQueuedTasks() {
        TrackedTask queued;
        while ((queued = removeNextTaskFromQueue()) != null) {
            queued.cancelQueuedTask();
        }
    }

    /** Returns a non-negative elapsed duration for lane metrics. */
    private static long elapsedNanos(long startedNanos, long finishedNanos) {
        long elapsed = finishedNanos - startedNanos;
        return elapsed < 0L ? 0L : elapsed;
    }

    private final class TrackedRunnable
            implements TrackedTask {
        private final Runnable delegate;
        private final TaskTiming timing = new TaskTiming();

        /** Wraps fire-and-forget work with separate timing and failure tracking. */
        private TrackedRunnable(Runnable delegate) {
            this.delegate = delegate;
        }

        /** Returns the measurement state for this queued Runnable. */
        @Override
        public TaskTiming timing() {
            return timing;
        }

        /** Fire-and-forget work has no Future that can be cancelled. */
        @Override
        public void cancelQueuedTask() {
            // Nothing to cancel for fire-and-forget work.
        }

        /** Runs one fire-and-forget task while recording timing and containing runtime failures. */
        @Override
        public void run() {
            timing.recordStartedAt(
                    System.nanoTime());
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
                timing.recordFinishedAt(
                        System.nanoTime());
                metrics.recordCompleted(
                        timing.queueWaitNanos(),
                        timing.executionNanos());
            }
        }
    }

    private final class TrackedFutureTask<R>
            extends FutureTask<R>
            implements TrackedTask {
        private final FatalTrackingCallable<R> trackedCallable;
        private final TaskTiming timing =
                new TaskTiming();

        /** Wraps result-bearing work in the FutureTask used as the public Future. */
        private TrackedFutureTask(Callable<R> work) {
            this(new FatalTrackingCallable<R>(work));
        }

        /** Initializes FutureTask and retains fatal-error tracking for lane failure propagation. */
        private TrackedFutureTask(FatalTrackingCallable<R> trackedCallable) {
            super(trackedCallable);
            this.trackedCallable = trackedCallable;
        }

        /** Returns the measurement state for this queued FutureTask. */
        @Override
        public TaskTiming timing() {
            return timing;
        }

        /** Cancels this queued FutureTask when lane failure prevents later execution. */
        @Override
        public void cancelQueuedTask() {
            cancel(false);
        }

        /** Runs one FutureTask, records timing and promotes fatal Errors to lane failure. */
        @Override
        public void run() {
            timing.recordStartedAt(
                    System.nanoTime());
            try {
                super.run();
            } finally {
                timing.recordFinishedAt(
                        System.nanoTime());
                metrics.recordCompleted(
                        timing.queueWaitNanos(),
                        timing.executionNanos());
                Error fatal =
                        trackedCallable.fatalError();
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

        /** Wraps a Callable so fatal Errors can be observed after FutureTask execution. */
        private FatalTrackingCallable(Callable<R> delegate) {
            this.delegate = delegate;
        }

        /** Delegates the Callable while retaining any fatal Error for the owning lane. */
        @Override
        public R call() throws Exception {
            try {
                return delegate.call();
            } catch (Error ex) {
                fatalError = ex;
                throw ex;
            }
        }

        /** Returns the fatal Error captured during the delegated call, if any. */
        private Error fatalError() {
            return fatalError;
        }
    }
}
