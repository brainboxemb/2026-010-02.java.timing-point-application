package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.function.IntSupplier;

/**
 * Lane-local measurements for {@link SerialExecutor}.
 *
 * <p>The execution primitive records facts here but does not own the diagnostic
 * model itself. Snapshot creation is pull-based and may allocate; hot-path
 * recording keeps only primitive state.</p>
 */
public final class SerialExecutorMetrics {
    private final IntSupplier queueDepthSupplier;
    private final boolean workerCpuTimeAttributable;

    private int highWaterMark;
    private long acceptedCount;
    private long fullCount;
    private long notRunningCount;
    private long completedCount;
    private long totalQueueWaitNanos;
    private long maxQueueWaitNanos;
    private long totalExecutionNanos;
    private long maxExecutionNanos;
    private volatile Thread standaloneWorkerThread;

    SerialExecutorMetrics(
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

    synchronized void recordAccepted(int queueDepth) {
        acceptedCount++;
        if (queueDepth > highWaterMark) {
            highWaterMark = queueDepth;
        }
    }

    synchronized void recordFull() {
        fullCount++;
    }

    synchronized void recordNotRunning() {
        notRunningCount++;
    }

    synchronized void recordCompleted(
            long queueWaitNanos,
            long executionNanos) {
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

    /**
     * Returns an immutable pull-based view of the current lane metrics.
     */
    public synchronized Snapshot snapshot() {
        return new Snapshot(
                queueDepthSupplier.getAsInt(),
                highWaterMark,
                acceptedCount,
                fullCount,
                notRunningCount,
                completedCount,
                totalQueueWaitNanos,
                maxQueueWaitNanos,
                totalExecutionNanos,
                maxExecutionNanos,
                workerThreadCpuTimeNanos());
    }

    /**
     * CPU time is attributable only when the lane owns its physical worker.
     *
     * <p>For a lane on a shared role executor, worker CPU time belongs to the
     * shared executor rather than to one lane and is therefore unavailable.</p>
     */
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
        private final int queueHighWaterMark;
        private final long acceptedCount;
        private final long fullCount;
        private final long notRunningCount;
        private final long completedCount;
        private final long totalQueueWaitNanos;
        private final long maxQueueWaitNanos;
        private final long totalExecutionNanos;
        private final long maxExecutionNanos;
        private final long workerThreadCpuTimeNanos;

        private Snapshot(
                int queueDepth,
                int queueHighWaterMark,
                long acceptedCount,
                long fullCount,
                long notRunningCount,
                long completedCount,
                long totalQueueWaitNanos,
                long maxQueueWaitNanos,
                long totalExecutionNanos,
                long maxExecutionNanos,
                long workerThreadCpuTimeNanos) {
            this.queueDepth = queueDepth;
            this.queueHighWaterMark = queueHighWaterMark;
            this.acceptedCount = acceptedCount;
            this.fullCount = fullCount;
            this.notRunningCount = notRunningCount;
            this.completedCount = completedCount;
            this.totalQueueWaitNanos = totalQueueWaitNanos;
            this.maxQueueWaitNanos = maxQueueWaitNanos;
            this.totalExecutionNanos = totalExecutionNanos;
            this.maxExecutionNanos = maxExecutionNanos;
            this.workerThreadCpuTimeNanos =
                    workerThreadCpuTimeNanos;
        }

        public int queueDepth() {
            return queueDepth;
        }

        public int queueHighWaterMark() {
            return queueHighWaterMark;
        }

        public long acceptedCount() {
            return acceptedCount;
        }

        public long fullCount() {
            return fullCount;
        }

        public long notRunningCount() {
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

        public long workerThreadCpuTimeNanos() {
            return workerThreadCpuTimeNanos;
        }
    }
}
