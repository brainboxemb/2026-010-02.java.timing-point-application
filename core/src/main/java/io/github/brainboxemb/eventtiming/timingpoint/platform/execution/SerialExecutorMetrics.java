package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

    private final AtomicInteger highWaterMark = new AtomicInteger();
    private final AtomicLong acceptedCount = new AtomicLong();
    private final AtomicLong fullCount = new AtomicLong();
    private final AtomicLong notRunningCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong totalQueueWaitNanos = new AtomicLong();
    private final AtomicLong maxQueueWaitNanos = new AtomicLong();
    private final AtomicLong totalExecutionNanos = new AtomicLong();
    private final AtomicLong maxExecutionNanos = new AtomicLong();
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

    void recordAccepted(int queueDepth) {
        acceptedCount.incrementAndGet();
        updateMaximum(highWaterMark, queueDepth);
    }

    void recordFull() {
        fullCount.incrementAndGet();
    }

    void recordNotRunning() {
        notRunningCount.incrementAndGet();
    }

    void recordCompleted(
            long queueWaitNanos,
            long executionNanos) {
        completedCount.incrementAndGet();
        totalQueueWaitNanos.addAndGet(queueWaitNanos);
        updateMaximum(maxQueueWaitNanos, queueWaitNanos);
        totalExecutionNanos.addAndGet(executionNanos);
        updateMaximum(maxExecutionNanos, executionNanos);
    }

    /**
     * Returns an immutable pull-based view of the current lane metrics.
     */
    public Snapshot snapshot() {
        return new Snapshot(
                queueDepthSupplier.getAsInt(),
                highWaterMark.get(),
                acceptedCount.get(),
                fullCount.get(),
                notRunningCount.get(),
                completedCount.get(),
                totalQueueWaitNanos.get(),
                maxQueueWaitNanos.get(),
                totalExecutionNanos.get(),
                maxExecutionNanos.get(),
                workerThreadCpuTimeNanos());
    }

    private static void updateMaximum(
            AtomicInteger maximum,
            int candidate) {
        int current = maximum.get();
        while (candidate > current
                && !maximum.compareAndSet(current, candidate)) {
            current = maximum.get();
        }
    }

    private static void updateMaximum(
            AtomicLong maximum,
            long candidate) {
        long current = maximum.get();
        while (candidate > current
                && !maximum.compareAndSet(current, candidate)) {
            current = maximum.get();
        }
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
