package oss.relay.eventtiming.timingpoint.platform.events;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe engineering metrics for synchronous event delivery.
 *
 * <p>The event owns timing; this object only stores the resulting counters so a
 * component-level metrics object may include them in its own snapshot without
 * duplicating stopwatch logic.</p>
 */
public final class EventMetrics {
    private final AtomicLong deliveries = new AtomicLong();
    private final AtomicLong listenerFailures = new AtomicLong();
    private final AtomicLong totalDeliveryNanos = new AtomicLong();
    private final AtomicLong maxDeliveryNanos = new AtomicLong();

    /** Records one completed event delivery. Package-private for EventWithMetrics. */
    void record(long elapsedNanos, int failureCount) {
        long safeElapsed = Math.max(0L, elapsedNanos);
        deliveries.incrementAndGet();
        listenerFailures.addAndGet(failureCount);
        totalDeliveryNanos.addAndGet(safeElapsed);
        updateMaximum(maxDeliveryNanos, safeElapsed);
    }

    /** Returns an immutable current snapshot. */
    public Snapshot snapshot() {
        return new Snapshot(
                deliveries.get(),
                listenerFailures.get(),
                totalDeliveryNanos.get(),
                maxDeliveryNanos.get());
    }

    private static void updateMaximum(AtomicLong maximum, long candidate) {
        long current = maximum.get();
        while (candidate > current
                && !maximum.compareAndSet(current, candidate)) {
            current = maximum.get();
        }
    }

    /** Immutable pull-based event-delivery metrics. */
    public static final class Snapshot {
        private final long deliveries;
        private final long listenerFailures;
        private final long totalDeliveryNanos;
        private final long maxDeliveryNanos;

        private Snapshot(
                long deliveries,
                long listenerFailures,
                long totalDeliveryNanos,
                long maxDeliveryNanos) {
            this.deliveries = deliveries;
            this.listenerFailures = listenerFailures;
            this.totalDeliveryNanos = totalDeliveryNanos;
            this.maxDeliveryNanos = maxDeliveryNanos;
        }

        public long deliveries() {
            return deliveries;
        }

        public long listenerFailures() {
            return listenerFailures;
        }

        public long totalDeliveryNanos() {
            return totalDeliveryNanos;
        }

        public long maxDeliveryNanos() {
            return maxDeliveryNanos;
        }
    }
}
