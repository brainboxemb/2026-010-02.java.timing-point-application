package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.IntSupplier;

/**
 * Lane-local measurements for {@link SerialScheduledExecutor}.
 *
 * <p>Scheduling/execution code records facts here; diagnostic state and its
 * immutable snapshot remain separate from the execution primitive.</p>
 */
public final class SerialScheduledExecutorMetrics {
    private final IntSupplier queueDepthSupplier;
    private final boolean workerCpuTimeAttributable;

    private long immediateAcceptedCount;
    private long immediateRejectedCount;
    private long scheduledRegistrationCount;
    private long scheduledCancellationCount;
    private long immediateExecutionCount;
    private long periodicExecutionCount;
    private long runtimeFailureCount;
    private volatile Thread standaloneWorkerThread;

    SerialScheduledExecutorMetrics(
            IntSupplier queueDepthSupplier,
            boolean workerCpuTimeAttributable) {
        if (queueDepthSupplier == null) {
            throw new IllegalArgumentException(
                    "queueDepthSupplier must not be null");
        }
        this.queueDepthSupplier = queueDepthSupplier;
        this.workerCpuTimeAttributable =
                workerCpuTimeAttributable;
    }

    void recordStandaloneWorkerThread(Thread thread) {
        standaloneWorkerThread = thread;
    }

    synchronized void recordImmediateAccepted() {
        immediateAcceptedCount++;
    }

    synchronized void recordImmediateRejected() {
        immediateRejectedCount++;
    }

    synchronized void recordScheduledRegistration() {
        scheduledRegistrationCount++;
    }

    synchronized void recordScheduledCancellation() {
        scheduledCancellationCount++;
    }

    synchronized void recordImmediateExecution() {
        immediateExecutionCount++;
    }

    synchronized void recordPeriodicExecution() {
        periodicExecutionCount++;
    }

    synchronized void recordRuntimeFailure() {
        runtimeFailureCount++;
    }

    /**
     * Returns an immutable pull-based view of the current scheduled-lane metrics.
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                queueDepthSupplier.getAsInt(),
                immediateAcceptedCount,
                immediateRejectedCount,
                scheduledRegistrationCount,
                scheduledCancellationCount,
                immediateExecutionCount,
                periodicExecutionCount,
                runtimeFailureCount,
                workerThreadCpuTimeNanos());
    }

    private long workerThreadCpuTimeNanos() {
        if (!workerCpuTimeAttributable) {
            return -1L;
        }
        Thread worker = standaloneWorkerThread;
        if (worker == null) {
            return -1L;
        }

        ThreadMXBean bean =
                ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported()
                || !bean.isThreadCpuTimeEnabled()) {
            return -1L;
        }
        long cpuTime =
                bean.getThreadCpuTime(worker.getId());
        return cpuTime < 0L ? -1L : cpuTime;
    }

    /** Immutable snapshot returned to diagnostic/engineering readers. */
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
            this.immediateAcceptedCount =
                    immediateAcceptedCount;
            this.immediateRejectedCount =
                    immediateRejectedCount;
            this.scheduledRegistrationCount =
                    scheduledRegistrationCount;
            this.scheduledCancellationCount =
                    scheduledCancellationCount;
            this.immediateExecutionCount =
                    immediateExecutionCount;
            this.periodicExecutionCount =
                    periodicExecutionCount;
            this.runtimeFailureCount =
                    runtimeFailureCount;
            this.workerThreadCpuTimeNanos =
                    workerThreadCpuTimeNanos;
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
