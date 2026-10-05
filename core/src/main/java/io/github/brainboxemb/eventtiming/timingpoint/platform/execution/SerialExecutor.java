package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

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
 * <p>A {@code SerialExecutor} is a logical lane, not necessarily a Java thread.
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
public final class SerialExecutor implements AutoCloseable {
    private static final Logger LOG =
            LoggerFactory.getLogger(SerialExecutor.class);

    /** Lifecycle of this logical lane, independent of any shared worker lifecycle. */
    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    /** Result of non-blocking admission to this lane's bounded queue. */
    public enum AdmissionResult {
        ACCEPTED,
        FULL,
        NOT_RUNNING
    }

    /** Admission result plus Future for result-bearing work when accepted. */
    public static final class SubmitResult<R> {
        private final AdmissionResult admission;
        private final Future<R> futureResult;

        SubmitResult(
                AdmissionResult admission,
                Future<R> futureResult) {
            this.admission = admission;
            this.futureResult = futureResult;
        }

        public AdmissionResult admission() {
            return admission;
        }

        public Future<R> futureResult() {
            if (futureResult == null) {
                throw new IllegalStateException(
                        "futureResult is unavailable because the work was not accepted");
            }
            return futureResult;
        }
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
    private final SerialExecutorMetrics metrics;

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
    public SerialExecutor(
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
        this.metrics = new SerialExecutorMetrics(queue::size);
    }

    private static void validateCapacity(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "capacity must be positive");
        }
    }

    private static String requireLaneName(String laneName) {
        if (laneName == null || laneName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "laneName must not be blank");
        }
        return laneName.trim();
    }

    /** Starts this logical lane; the supplied worker is already externally owned. */
    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "SerialExecutor can only start from NEW; current state="
                            + state);
        }
        state = State.RUNNING;
    }

    /**
     * Attempts to admit result-bearing work without waiting for queue space.
     *
     * <p>The returned Future is available only when admission is ACCEPTED.</p>
     */
    public <R> SubmitResult<R> submit(Callable<R> work) {
        if (work == null) {
            throw new IllegalArgumentException(
                    "work must not be null");
        }

        TrackedFutureTask<R> task =
                new TrackedFutureTask<R>(work);
        AdmissionResult admission = admit(task);
        return new SubmitResult<R>(
                admission,
                admission == AdmissionResult.ACCEPTED
                        ? task
                        : null);
    }

    /**
     * Attempts to admit fire-and-forget work without waiting for queue space.
     */
    public AdmissionResult offer(Runnable work) {
        if (work == null) {
            throw new IllegalArgumentException(
                    "work must not be null");
        }
        return admit(new TrackedRunnable(work));
    }

    private AdmissionResult admit(TrackedTask task) {
        synchronized (this) {
            if (state != State.RUNNING) {
                metrics.recordNotRunning();
                return AdmissionResult.NOT_RUNNING;
            }

            task.markAccepted(System.nanoTime());
            if (!queue.offer(task)) {
                metrics.recordFull();
                return AdmissionResult.FULL;
            }

            if (!drainScheduled) {
                drainScheduled = true;
                if (!scheduleDrainLocked()) {
                    queue.remove(task);
                    task.cancelIfFuture();
                    metrics.recordNotRunning();
                    return AdmissionResult.NOT_RUNNING;
                }
            }

            metrics.recordAccepted(queue.size());
            return AdmissionResult.ACCEPTED;
        }
    }

    private boolean scheduleDrainLocked() {
        try {
            workerExecutor.execute(this::drainOne);
            return true;
        } catch (RejectedExecutionException ex) {
            failure = ex;
            state = State.FAILED;
            drainScheduled = false;
            cancelQueuedLocked();
            notifyAll();
            return false;
        }
    }

    /**
     * Executes one lane item and resubmits one drain token when more work exists.
     *
     * <p>Processing one item per token is intentional: on a shared one-worker
     * role executor, another lane already waiting in the role queue can run
     * before this lane resubmits its next item. One busy TimingNode therefore
     * does not drain its complete backlog before another node gets a turn.</p>
     */
    private void drainOne() {
        final TrackedTask task;
        synchronized (this) {
            if (state == State.FAILED) {
                drainScheduled = false;
                notifyAll();
                return;
            }

            task = queue.poll();
            if (task == null) {
                drainScheduled = false;
                notifyAll();
                return;
            }
            taskRunning = true;
        }

        try {
            task.run();
        } finally {
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

                if (!scheduleDrainLocked()) {
                    notifyAll();
                }
            }
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Throwable failure() {
        return failure;
    }

    /** Returns the separate pull-based metrics owner for this lane. */
    public SerialExecutorMetrics metrics() {
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

    private void cancelQueuedLocked() {
        TrackedTask queued;
        while ((queued = queue.poll()) != null) {
            queued.cancelIfFuture();
        }
    }

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
