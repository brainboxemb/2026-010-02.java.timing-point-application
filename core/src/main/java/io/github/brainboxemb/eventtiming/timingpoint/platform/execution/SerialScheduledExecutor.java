package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One JDK-backed serial execution lane with delayed/fixed-delay scheduling.
 *
 * <p>This executor does not own a domain input queue. Components such as
 * TagProcessor keep their bounded input data separately and use this type only
 * for coalesced execution and scheduled housekeeping.</p>
 */
public final class SerialScheduledExecutor implements AutoCloseable {
    private static final Logger LOG =
            LoggerFactory.getLogger(SerialScheduledExecutor.class);

    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    public interface ScheduledTask extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Component-owned engineering metrics for the scheduled serial lane.
     *
     * <p>These values describe executor work only. They deliberately do not
     * mirror a component's own input queue, such as TagProcessor's bounded
     * TagObservation queue. Snapshot creation is an explicit diagnostic read and
     * may allocate or query JVM thread-management state.</p>
     */
    public static final class Metrics {
        private final SerialScheduledExecutor owner;

        private long immediateAcceptedCount;
        private long immediateRejectedCount;
        private long scheduledRegistrationCount;
        private long scheduledCancellationCount;
        private volatile long immediateExecutionCount;
        private volatile long periodicExecutionCount;
        private volatile long runtimeFailureCount;
        private volatile Thread workerThread;

        private Metrics(SerialScheduledExecutor owner) {
            this.owner = owner;
        }

        private void recordWorkerThread(Thread thread) {
            workerThread = thread;
        }

        private void recordImmediateAccepted() {
            synchronized (owner) {
                immediateAcceptedCount++;
            }
        }

        private void recordImmediateRejected() {
            synchronized (owner) {
                immediateRejectedCount++;
            }
        }

        private void recordScheduledRegistration() {
            synchronized (owner) {
                scheduledRegistrationCount++;
            }
        }

        private void recordScheduledCancellation() {
            synchronized (owner) {
                scheduledCancellationCount++;
            }
        }

        private void recordImmediateExecution() {
            immediateExecutionCount++;
        }

        private void recordPeriodicExecution() {
            periodicExecutionCount++;
        }

        private void recordRuntimeFailure() {
            runtimeFailureCount++;
        }

        /** Returns an immutable pull-based snapshot of this lane's metrics. */
        public Snapshot snapshot() {
            ScheduledThreadPoolExecutor active;
            int queueDepth;
            long immediateAccepted;
            long immediateRejected;
            long scheduledRegistrations;
            long scheduledCancellations;

            synchronized (owner) {
                active = owner.executor;
                queueDepth = active == null ? 0 : active.getQueue().size();
                immediateAccepted = immediateAcceptedCount;
                immediateRejected = immediateRejectedCount;
                scheduledRegistrations = scheduledRegistrationCount;
                scheduledCancellations = scheduledCancellationCount;
            }

            return new Snapshot(
                    queueDepth,
                    immediateAccepted,
                    immediateRejected,
                    scheduledRegistrations,
                    scheduledCancellations,
                    immediateExecutionCount,
                    periodicExecutionCount,
                    runtimeFailureCount,
                    workerThreadCpuTimeNanos());
        }

        private long workerThreadCpuTimeNanos() {
            Thread worker = workerThread;
            if (worker == null) {
                return -1L;
            }

            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!bean.isThreadCpuTimeSupported()
                    || !bean.isThreadCpuTimeEnabled()) {
                return -1L;
            }
            long cpuTime = bean.getThreadCpuTime(worker.getId());
            return cpuTime < 0L ? -1L : cpuTime;
        }

        /** Immutable point-in-time engineering view of scheduled-lane metrics. */
        public static final class Snapshot {
            private final int queueDepth;
            private final long immediateAcceptedCount;
            private final long immediateRejectedCount;
            private final long scheduledRegistrationCount;
            private final long scheduledCancellationCount;
            private final long immediateExecutionCount;
            private final long periodicExecutionCount;
            private final long runtimeFailureCount;
            private final long workerThreadCpuTimeNanos;

            private Snapshot(
                    int queueDepth,
                    long immediateAcceptedCount,
                    long immediateRejectedCount,
                    long scheduledRegistrationCount,
                    long scheduledCancellationCount,
                    long immediateExecutionCount,
                    long periodicExecutionCount,
                    long runtimeFailureCount,
                    long workerThreadCpuTimeNanos) {
                this.queueDepth = queueDepth;
                this.immediateAcceptedCount = immediateAcceptedCount;
                this.immediateRejectedCount = immediateRejectedCount;
                this.scheduledRegistrationCount = scheduledRegistrationCount;
                this.scheduledCancellationCount = scheduledCancellationCount;
                this.immediateExecutionCount = immediateExecutionCount;
                this.periodicExecutionCount = periodicExecutionCount;
                this.runtimeFailureCount = runtimeFailureCount;
                this.workerThreadCpuTimeNanos = workerThreadCpuTimeNanos;
            }

            public int queueDepth() {
                return queueDepth;
            }

            public long immediateAcceptedCount() {
                return immediateAcceptedCount;
            }

            public long immediateRejectedCount() {
                return immediateRejectedCount;
            }

            public long scheduledRegistrationCount() {
                return scheduledRegistrationCount;
            }

            public long scheduledCancellationCount() {
                return scheduledCancellationCount;
            }

            public long immediateExecutionCount() {
                return immediateExecutionCount;
            }

            public long periodicExecutionCount() {
                return periodicExecutionCount;
            }

            public long runtimeFailureCount() {
                return runtimeFailureCount;
            }

            public long workerThreadCpuTimeNanos() {
                return workerThreadCpuTimeNanos;
            }
        }
    }

    private final String threadName;
    private final Metrics metrics = new Metrics(this);

    private State state = State.NEW;
    private ScheduledThreadPoolExecutor executor;
    private Throwable failure;

    public SerialScheduledExecutor(String threadName) {
        if (threadName == null || threadName.trim().isEmpty()) {
            throw new IllegalArgumentException("threadName must not be blank");
        }
        this.threadName = threadName.trim();
    }

    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "SerialScheduledExecutor can only start from NEW; current state="
                            + state);
        }

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadName);
            metrics.recordWorkerThread(thread);
            return thread;
        };
        executor = new ScheduledThreadPoolExecutor(1, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        /*
         * Immediate execute(...) work is represented by zero-delay scheduled
         * tasks in ScheduledThreadPoolExecutor. Keep already accepted immediate
         * drain work executable during graceful shutdown. Periodic work is
         * cancelled separately by its owner and must not continue after shutdown.
         */
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(true);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        state = State.RUNNING;
        executor.prestartCoreThread();
    }

    /**
     * Queues one immediate operation on the serial lane.
     *
     * @return false when the executor no longer accepts work
     */
    public boolean execute(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }

        ScheduledThreadPoolExecutor active;
        synchronized (this) {
            if (state != State.RUNNING) {
                metrics.recordImmediateRejected();
                return false;
            }
            active = executor;
        }

        try {
            active.execute(wrapImmediate(task));
            metrics.recordImmediateAccepted();
            return true;
        } catch (RejectedExecutionException ex) {
            metrics.recordImmediateRejected();
            return false;
        }
    }

    public ScheduledTask scheduleWithFixedDelay(
            Runnable task,
            long delayNanos) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (delayNanos < 1L) {
            throw new IllegalArgumentException("delayNanos must be positive");
        }

        ScheduledThreadPoolExecutor active;
        synchronized (this) {
            if (state != State.RUNNING) {
                throw new IllegalStateException(
                        "SerialScheduledExecutor is not running");
            }
            active = executor;
        }

        try {
            ScheduledFuture<?> future = active.scheduleWithFixedDelay(
                    wrapPeriodic(task),
                    delayNanos,
                    delayNanos,
                    TimeUnit.NANOSECONDS);
            metrics.recordScheduledRegistration();
            return () -> {
                if (future.cancel(false)) {
                    metrics.recordScheduledCancellation();
                }
            };
        } catch (RejectedExecutionException ex) {
            throw new IllegalStateException(
                    "SerialScheduledExecutor stopped while scheduling work",
                    ex);
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Throwable failure() {
        return failure;
    }

    /** Returns the stable component-owned metrics handle for this executor. */
    public Metrics metrics() {
        return metrics;
    }

    @Override
    public void close() {
        ScheduledThreadPoolExecutor active;
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

        synchronized (this) {
            if (state != State.FAILED) {
                state = State.STOPPED;
            }
        }
    }

    private Runnable wrapImmediate(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                metrics.recordRuntimeFailure();
                LOG.warn("Serial scheduled executor task failed", ex);
            } catch (Error ex) {
                metrics.recordRuntimeFailure();
                markFailed(ex);
                throw ex;
            } finally {
                metrics.recordImmediateExecution();
            }
        };
    }

    private Runnable wrapPeriodic(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                metrics.recordRuntimeFailure();
                /*
                 * ScheduledThreadPoolExecutor suppresses later fixed-delay
                 * executions when a task escapes with an exception. Keep the
                 * lane alive and make the failure visible instead.
                 */
                LOG.warn("Serial scheduled periodic task failed", ex);
            } catch (Error ex) {
                metrics.recordRuntimeFailure();
                markFailed(ex);
                throw ex;
            } finally {
                metrics.recordPeriodicExecution();
            }
        };
    }

    private void markFailed(Error cause) {
        ScheduledThreadPoolExecutor active;
        synchronized (this) {
            if (state == State.FAILED) {
                return;
            }
            failure = cause;
            state = State.FAILED;
            active = executor;
        }
        if (active != null) {
            active.shutdownNow();
        }
    }
}
