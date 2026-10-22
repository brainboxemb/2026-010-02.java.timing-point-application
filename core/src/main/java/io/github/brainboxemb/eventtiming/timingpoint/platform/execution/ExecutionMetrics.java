package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * Queue-admission and execution measurements shared by independent execution primitives.
 *
 * <p>The execution primitive records facts here but does not own the diagnostic
 * model itself. Snapshot creation is pull-based and may allocate; hot-path
 * recording keeps only primitive state.</p>
 */
public final class ExecutionMetrics {
    private final IntSupplier queueDepthSupplier;
    private final AtomicInteger highWaterMark = new AtomicInteger();
    private final AtomicLong queuedCount = new AtomicLong();
    private final AtomicLong fullCount = new AtomicLong();
    private final AtomicLong notRunningCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong totalQueueWaitNanos = new AtomicLong();
    private final AtomicLong maxQueueWaitNanos = new AtomicLong();
    private final AtomicLong totalExecutionNanos = new AtomicLong();
    private final AtomicLong maxExecutionNanos = new AtomicLong();

    /** Creates execution metrics backed by the supplied current queue-depth reader. */
    public ExecutionMetrics(
            IntSupplier queueDepthSupplier) {
        if (queueDepthSupplier == null) {
            throw new IllegalArgumentException(
                    "queueDepthSupplier must not be null");
        }
        this.queueDepthSupplier = queueDepthSupplier;
    }

    /** Records one item that was successfully queued and updates the queue high-water mark. */
    public void recordQueued(int queueDepth) {
        queuedCount.incrementAndGet();
        updateMaximum(highWaterMark, queueDepth);
    }

    /** Records one item rejected because the bounded bounded queue was full. */
    public void recordFull() {
        fullCount.incrementAndGet();
    }

    /** Records one item rejected because the owner was not accepting work. */
    public void recordNotRunning() {
        notRunningCount.incrementAndGet();
    }

    /** Records queue-wait and execution durations for one completed item. */
    public void recordCompleted(
            long queueWaitNanos,
            long executionNanos) {
        completedCount.incrementAndGet();
        totalQueueWaitNanos.addAndGet(queueWaitNanos);
        updateMaximum(maxQueueWaitNanos, queueWaitNanos);
        totalExecutionNanos.addAndGet(executionNanos);
        updateMaximum(maxExecutionNanos, executionNanos);
    }

    /**
     * Returns an immutable pull-based view of the current execution metrics.
     */
    public Snapshot snapshot() {
        return new Snapshot(
                queueDepthSupplier.getAsInt(),
                highWaterMark.get(),
                queuedCount.get(),
                fullCount.get(),
                notRunningCount.get(),
                completedCount.get(),
                totalQueueWaitNanos.get(),
                maxQueueWaitNanos.get(),
                totalExecutionNanos.get(),
                maxExecutionNanos.get());
    }

    /** Atomically raises an integer maximum when the candidate is larger. */
    private static void updateMaximum(
            AtomicInteger maximum,
            int candidate) {
        int current = maximum.get();
        while (candidate > current
                && !maximum.compareAndSet(current, candidate)) {
            current = maximum.get();
        }
    }

    /** Atomically raises a long maximum when the candidate is larger. */
    private static void updateMaximum(
            AtomicLong maximum,
            long candidate) {
        long current = maximum.get();
        while (candidate > current
                && !maximum.compareAndSet(current, candidate)) {
            current = maximum.get();
        }
    }

    /** Immutable snapshot returned to diagnostic/engineering readers. */
    public static final class Snapshot {
        private final int queueDepth;
        private final int queueHighWaterMark;
        private final long queuedCount;
        private final long fullCount;
        private final long notRunningCount;
        private final long completedCount;
        private final long totalQueueWaitNanos;
        private final long maxQueueWaitNanos;
        private final long totalExecutionNanos;
        private final long maxExecutionNanos;

        /** Creates one immutable copy of all current execution metric values. */
        private Snapshot(
                int queueDepth,
                int queueHighWaterMark,
                long queuedCount,
                long fullCount,
                long notRunningCount,
                long completedCount,
                long totalQueueWaitNanos,
                long maxQueueWaitNanos,
                long totalExecutionNanos,
                long maxExecutionNanos) {
            this.queueDepth = queueDepth;
            this.queueHighWaterMark = queueHighWaterMark;
            this.queuedCount = queuedCount;
            this.fullCount = fullCount;
            this.notRunningCount = notRunningCount;
            this.completedCount = completedCount;
            this.totalQueueWaitNanos = totalQueueWaitNanos;
            this.maxQueueWaitNanos = maxQueueWaitNanos;
            this.totalExecutionNanos = totalExecutionNanos;
            this.maxExecutionNanos = maxExecutionNanos;
        }

        /** Returns the current number of items waiting in the lane queue. */
        public int queueDepth() {
            return queueDepth;
        }

        /** Returns the largest observed lane queue depth. */
        public int queueHighWaterMark() {
            return queueHighWaterMark;
        }

        /** Returns how many items were successfully queued. */
        public long queuedCount() {
            return queuedCount;
        }

        /** Returns how many queue attempts were rejected because capacity was full. */
        public long fullCount() {
            return fullCount;
        }

        /** Returns how many queue attempts were rejected because the lane was not running. */
        public long notRunningCount() {
            return notRunningCount;
        }

        /** Returns how many queued items completed execution. */
        public long completedCount() {
            return completedCount;
        }

        /** Returns cumulative time spent waiting in the lane queue. */
        public long totalQueueWaitNanos() {
            return totalQueueWaitNanos;
        }

        /** Returns the longest observed queue wait. */
        public long maxQueueWaitNanos() {
            return maxQueueWaitNanos;
        }

        /** Returns cumulative item execution time. */
        public long totalExecutionNanos() {
            return totalExecutionNanos;
        }

        /** Returns the longest observed item execution duration. */
        public long maxExecutionNanos() {
            return maxExecutionNanos;
        }

    }
}
