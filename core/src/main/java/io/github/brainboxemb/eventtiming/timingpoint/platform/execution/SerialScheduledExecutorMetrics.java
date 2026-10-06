package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * Lane-local measurements for {@link SerialScheduledExecutor}.
 *
 * <p>Scheduling/execution code records facts here; diagnostic state and its
 * immutable snapshot remain separate from the execution primitive.</p>
 */
public final class SerialScheduledExecutorMetrics {
    private final IntSupplier queueDepthSupplier;
    private final AtomicLong immediateAcceptedCount = new AtomicLong();
    private final AtomicLong immediateRejectedCount = new AtomicLong();
    private final AtomicLong scheduledRegistrationCount = new AtomicLong();
    private final AtomicLong scheduledCancellationCount = new AtomicLong();
    private final AtomicLong immediateExecutionCount = new AtomicLong();
    private final AtomicLong delayedExecutionCount = new AtomicLong();
    private final AtomicLong periodicExecutionCount = new AtomicLong();
    private final AtomicLong runtimeFailureCount = new AtomicLong();

    SerialScheduledExecutorMetrics(
            IntSupplier queueDepthSupplier) {
        if (queueDepthSupplier == null) {
            throw new IllegalArgumentException(
                    "queueDepthSupplier must not be null");
        }
        this.queueDepthSupplier = queueDepthSupplier;
    }

    void recordImmediateAccepted() {
        immediateAcceptedCount.incrementAndGet();
    }

    void recordImmediateRejected() {
        immediateRejectedCount.incrementAndGet();
    }

    void recordScheduledRegistration() {
        scheduledRegistrationCount.incrementAndGet();
    }

    void recordScheduledCancellation() {
        scheduledCancellationCount.incrementAndGet();
    }

    void recordImmediateExecution() {
        immediateExecutionCount.incrementAndGet();
    }

    void recordDelayedExecution() {
        delayedExecutionCount.incrementAndGet();
    }

    void recordPeriodicExecution() {
        periodicExecutionCount.incrementAndGet();
    }

    void recordRuntimeFailure() {
        runtimeFailureCount.incrementAndGet();
    }

    /**
     * Returns an immutable pull-based view of the current scheduled-lane metrics.
     */
    public Snapshot snapshot() {
        return new Snapshot(
                queueDepthSupplier.getAsInt(),
                immediateAcceptedCount.get(),
                immediateRejectedCount.get(),
                scheduledRegistrationCount.get(),
                scheduledCancellationCount.get(),
                immediateExecutionCount.get(),
                delayedExecutionCount.get(),
                periodicExecutionCount.get(),
                runtimeFailureCount.get(),
                -1L);
    }

    /** Immutable snapshot returned to diagnostic/engineering readers. */
    public static final class Snapshot {
        private final int queueDepth;
        private final long immediateAcceptedCount;
        private final long immediateRejectedCount;
        private final long scheduledRegistrationCount;
        private final long scheduledCancellationCount;
        private final long immediateExecutionCount;
        private final long delayedExecutionCount;
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
                long delayedExecutionCount,
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
            this.delayedExecutionCount =
                    delayedExecutionCount;
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

        public long delayedExecutionCount() {
            return delayedExecutionCount;
        }

        public long periodicExecutionCount() {
            return periodicExecutionCount;
        }

        public long runtimeFailureCount() {
            return runtimeFailureCount;
        }

        /**
         * Returns {@code -1}: physical worker CPU time belongs to the externally
         * owned worker and is not attributable to one logical lane.
         */
        public long workerThreadCpuTimeNanos() {
            return workerThreadCpuTimeNanos;
        }
    }
}
