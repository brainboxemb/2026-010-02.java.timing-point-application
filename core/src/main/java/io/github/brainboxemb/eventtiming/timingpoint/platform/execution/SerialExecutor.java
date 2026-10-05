package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bounded serial execution lane.
 *
 * <p>The lane owns bounded admission, FIFO ordering, lifecycle and lane-local
 * metrics. Production composition may supply a shared role executor; in that
 * mode multiple SerialExecutor instances keep independent queues while one
 * physical worker services their drain tasks. The standalone constructor keeps
 * a one-thread JDK executor for focused tests and isolated uses.</p>
 */
public final class SerialExecutor implements AutoCloseable {
    private static final Logger LOG =
            LoggerFactory.getLogger(SerialExecutor.class);

    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    public enum AdmissionResult {
        ACCEPTED,
        FULL,
        NOT_RUNNING
    }

    public static final class SubmitResult<R> {
        private final AdmissionResult admission;
        private final Future<R> futureResult;

        private SubmitResult(
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

    private interface TrackedTask extends Runnable {
        void markAccepted(long acceptedAtNanos);

        void cancelIfFuture();
    }

    private final int capacity;
    private final String laneName;
    private final ExecutorService suppliedBackingExecutor;
    private final boolean ownsBackingExecutor;
    private final ArrayBlockingQueue<TrackedTask> queue;
    private final SerialExecutorMetrics metrics;

    private State state = State.NEW;
    private ExecutorService backingExecutor;
    private Throwable failure;
    private boolean drainScheduled;
    private boolean taskRunning;

    /**
     * Standalone lane with its own physical worker.
     *
     * <p>Production runtime composition should prefer the shared-executor
     * constructor so one worker can service multiple logical lanes.</p>
     */
    public SerialExecutor(int capacity, String threadName) {
        this(capacity, threadName, null, true);
    }

    /**
     * Logical serial lane serviced by a runtime-owned shared role executor.
     */
    public SerialExecutor(
            int capacity,
            String laneName,
            ExecutorService sharedExecutor) {
        this(capacity, laneName, sharedExecutor, false);
    }

    private SerialExecutor(
            int capacity,
            String laneName,
            ExecutorService suppliedBackingExecutor,
            boolean ownsBackingExecutor) {
        if (capacity < 1) {
            throw new IllegalArgumentException(
                    "capacity must be positive");
        }
        if (laneName == null || laneName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "laneName must not be blank");
        }
        if (!ownsBackingExecutor && suppliedBackingExecutor == null) {
            throw new IllegalArgumentException(
                    "sharedExecutor must not be null");
        }

        this.capacity = capacity;
        this.laneName = laneName.trim();
        this.suppliedBackingExecutor = suppliedBackingExecutor;
        this.ownsBackingExecutor = ownsBackingExecutor;
        this.queue = new ArrayBlockingQueue<TrackedTask>(capacity);
        this.metrics = new SerialExecutorMetrics(
                queue::size,
                ownsBackingExecutor);
    }

    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "SerialExecutor can only start from NEW; current state="
                            + state);
        }

        if (ownsBackingExecutor) {
            ThreadFactory threadFactory = runnable -> {
                Thread thread = new Thread(runnable, laneName);
                metrics.recordStandaloneWorkerThread(thread);
                return thread;
            };

            /*
             * Workload buffering lives in the lane-local bounded queue. The
             * backing executor only ever needs room for the next drain token.
             */
            ThreadPoolExecutor standalone =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            0L,
                            TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<Runnable>(1),
                            threadFactory,
                            new ThreadPoolExecutor.AbortPolicy());
            standalone.prestartCoreThread();
            backingExecutor = standalone;
        } else {
            backingExecutor = suppliedBackingExecutor;
        }

        state = State.RUNNING;
    }

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
            backingExecutor.execute(this::drainOne);
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

    public SerialExecutorMetrics metrics() {
        return metrics;
    }

    @Override
    public void close() {
        ExecutorService owned = null;

        synchronized (this) {
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

            if (ownsBackingExecutor) {
                owned = backingExecutor;
            }
        }

        if (owned != null) {
            owned.shutdown();
            awaitTermination(owned);
        }
    }

    private static void awaitTermination(
            ExecutorService executor) {
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(
                        100L,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void markFailed(Error cause) {
        ExecutorService owned = null;
        synchronized (this) {
            if (state == State.FAILED) {
                return;
            }
            failure = cause;
            state = State.FAILED;
            cancelQueuedLocked();
            drainScheduled = false;
            notifyAll();
            if (ownsBackingExecutor) {
                owned = backingExecutor;
            }
        }

        /*
         * A lane fault must not stop a runtime-owned shared role executor.
         * Standalone lanes still own and stop their private worker.
         */
        if (owned != null) {
            owned.shutdownNow();
        }
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
