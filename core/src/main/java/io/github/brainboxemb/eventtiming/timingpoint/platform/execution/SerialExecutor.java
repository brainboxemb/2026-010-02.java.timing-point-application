package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded one-at-a-time execution backed by a JDK ThreadPoolExecutor.
 *
 * <p>The JDK owns thread coordination and queue waiting. This class owns the
 * project semantics around bounded admission, lifecycle, processed Future
 * results and engineering counters.</p>
 */
public final class SerialExecutor implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SerialExecutor.class);

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
    }

    private final int capacity;
    private final String threadName;

    private State state = State.NEW;
    private ThreadPoolExecutor executor;
    private volatile Thread workerThread;
    private Throwable failure;
    private int highWaterMark;
    private long acceptedCount;
    private long fullCount;
    private long notRunningCount;
    private volatile long completedCount;
    private volatile long totalQueueWaitNanos;
    private volatile long maxQueueWaitNanos;
    private volatile long totalExecutionNanos;
    private volatile long maxExecutionNanos;

    public SerialExecutor(int capacity, String threadName) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (threadName == null || threadName.trim().isEmpty()) {
            throw new IllegalArgumentException("threadName must not be blank");
        }
        this.capacity = capacity;
        this.threadName = threadName.trim();
    }

    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "SerialExecutor can only start from NEW; current state=" + state);
        }

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadName);
            workerThread = thread;
            return thread;
        };

        executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(capacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        state = State.RUNNING;
        executor.prestartCoreThread();
    }

    public <R> SubmitResult<R> submit(Callable<R> work) {
        if (work == null) {
            throw new IllegalArgumentException("work must not be null");
        }

        TrackedFutureTask<R> task = new TrackedFutureTask<>(work);
        AdmissionResult admission = admit(task);
        return new SubmitResult<>(
                admission,
                admission == AdmissionResult.ACCEPTED ? task : null);
    }

    public AdmissionResult offer(Runnable work) {
        if (work == null) {
            throw new IllegalArgumentException("work must not be null");
        }
        return admit(new TrackedRunnable(work));
    }

    private AdmissionResult admit(TrackedTask task) {
        ThreadPoolExecutor active;
        synchronized (this) {
            if (state != State.RUNNING) {
                notRunningCount++;
                return AdmissionResult.NOT_RUNNING;
            }
            active = executor;
            task.markAccepted(System.nanoTime());
        }

        try {
            active.execute(task);
        } catch (RejectedExecutionException ex) {
            synchronized (this) {
                if (state != State.RUNNING || active.isShutdown()) {
                    notRunningCount++;
                    return AdmissionResult.NOT_RUNNING;
                }
                fullCount++;
                return AdmissionResult.FULL;
            }
        }

        synchronized (this) {
            acceptedCount++;
            int depth = active.getQueue().size();
            if (depth > highWaterMark) {
                highWaterMark = depth;
            }
        }
        return AdmissionResult.ACCEPTED;
    }

    public synchronized State state() {
        return state;
    }

    public int queueDepth() {
        ThreadPoolExecutor active;
        synchronized (this) {
            active = executor;
        }
        return active == null ? 0 : active.getQueue().size();
    }

    public synchronized long acceptedCount() {
        return acceptedCount;
    }

    public synchronized long fullCount() {
        return fullCount;
    }

    public synchronized long notRunningCount() {
        return notRunningCount;
    }

    public long completedCount() {
        return completedCount;
    }

    public long totalQueueWaitNanos() {
        return totalQueueWaitNanos;
    }

    public long maxQueueWaitNanos() {
        return maxQueueWaitNanos;
    }

    public long totalExecutionNanos() {
        return totalExecutionNanos;
    }

    public long maxExecutionNanos() {
        return maxExecutionNanos;
    }

    public synchronized int highWaterMark() {
        return highWaterMark;
    }

    public synchronized Throwable failure() {
        return failure;
    }

    public long threadCpuTimeNanos() {
        Thread worker = workerThread;
        if (worker == null) {
            return -1L;
        }

        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported() || !bean.isThreadCpuTimeEnabled()) {
            return -1L;
        }
        long cpuTime = bean.getThreadCpuTime(worker.getId());
        return cpuTime < 0L ? -1L : cpuTime;
    }

    @Override
    public void close() {
        ThreadPoolExecutor active;
        synchronized (this) {
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
            active = executor;
        }

        active.shutdown();
        awaitTermination(active);

        synchronized (this) {
            if (state != State.FAILED) {
                state = State.STOPPED;
            }
        }
    }

    private void awaitTermination(ThreadPoolExecutor active) {
        boolean interrupted = false;
        while (!active.isTerminated()) {
            try {
                active.awaitTermination(100L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordCompleted(long queueWaitNanos, long executionNanos) {
        completedCount++;
        totalQueueWaitNanos += queueWaitNanos;
        if (queueWaitNanos > maxQueueWaitNanos) {
            maxQueueWaitNanos = queueWaitNanos;
        }
        totalExecutionNanos += executionNanos;
        if (executionNanos > maxExecutionNanos) {
            maxExecutionNanos = executionNanos;
        }
    }

    private void markFailed(Error cause) {
        ThreadPoolExecutor active;
        synchronized (this) {
            if (state == State.FAILED) {
                return;
            }
            failure = cause;
            state = State.FAILED;
            active = executor;
        }

        if (active == null) {
            return;
        }
        List<Runnable> queued = active.shutdownNow();
        for (Runnable task : queued) {
            if (task instanceof Future<?>) {
                ((Future<?>) task).cancel(false);
            }
        }
    }

    private static long elapsedNanos(long startedNanos, long finishedNanos) {
        long elapsed = finishedNanos - startedNanos;
        return elapsed < 0L ? 0L : elapsed;
    }

    private final class TrackedRunnable implements TrackedTask {
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
        public void run() {
            long startedNanos = System.nanoTime();
            try {
                delegate.run();
            } catch (RuntimeException ex) {
                LOG.warn("Serial executor task failed", ex);
            } catch (Error ex) {
                markFailed(ex);
                throw ex;
            } finally {
                recordCompleted(
                        elapsedNanos(acceptedAtNanos, startedNanos),
                        elapsedNanos(startedNanos, System.nanoTime()));
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

        private TrackedFutureTask(FatalTrackingCallable<R> trackedCallable) {
            super(trackedCallable);
            this.trackedCallable = trackedCallable;
        }

        @Override
        public void markAccepted(long acceptedAtNanos) {
            this.acceptedAtNanos = acceptedAtNanos;
        }

        @Override
        public void run() {
            long startedNanos = System.nanoTime();
            try {
                super.run();
            } finally {
                recordCompleted(
                        elapsedNanos(acceptedAtNanos, startedNanos),
                        elapsedNanos(startedNanos, System.nanoTime()));
                Error fatal = trackedCallable.fatalError();
                if (fatal != null) {
                    markFailed(fatal);
                }
            }
        }
    }

    private static final class FatalTrackingCallable<R> implements Callable<R> {
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
