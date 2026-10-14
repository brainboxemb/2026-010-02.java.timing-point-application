package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Component-owned runtime metrics for the tag-processing path.
 *
 * <p>Hot-path record methods update only primitive atomic counters. Snapshot
 * creation is explicit and may allocate; callers use it for pull-based
 * engineering/diagnostic reads rather than continuously mirroring these values
 * into another status model.</p>
 */
public final class TagProcessingMetrics {
    private final AtomicLong observationCount = new AtomicLong();
    private final AtomicLong observationQueueFullCount = new AtomicLong();
    private final AtomicLong processorNotRunningCount = new AtomicLong();
    private final AtomicLong closedBurstCount = new AtomicLong();
    private final AtomicLong mappedCount = new AtomicLong();
    private final AtomicLong unmappedCount = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();

    void recordObservation() {
        observationCount.incrementAndGet();
    }

    void recordObservationQueueFull() {
        observationQueueFullCount.incrementAndGet();
    }

    void recordProcessorNotRunning() {
        processorNotRunningCount.incrementAndGet();
    }

    void recordClosedBurst() {
        closedBurstCount.incrementAndGet();
    }

    void recordMapped() {
        mappedCount.incrementAndGet();
    }

    void recordUnmapped() {
        unmappedCount.incrementAndGet();
    }

    void recordDuplicate() {
        duplicateCount.incrementAndGet();
    }

    /**
     * Returns an immutable pull-based view of the current processing metrics.
     *
     * <p>The snapshot is diagnostic and may allocate. Recording itself remains
     * allocation-free apart from the AtomicLong instances owned by this component.</p>
     */
    public Snapshot snapshot() {
        return new Snapshot(
                observationCount.get(),
                observationQueueFullCount.get(),
                processorNotRunningCount.get(),
                closedBurstCount.get(),
                mappedCount.get(),
                unmappedCount.get(),
                duplicateCount.get());
    }

    public static final class Snapshot {
        private final long observations;
        private final long observationQueueFull;
        private final long processorNotRunning;
        private final long closedBursts;
        private final long mapped;
        private final long unmapped;
        private final long duplicates;

        private Snapshot(
                long observations,
                long observationQueueFull,
                long processorNotRunning,
                long closedBursts,
                long mapped,
                long unmapped,
                long duplicates) {
            this.observations = observations;
            this.observationQueueFull = observationQueueFull;
            this.processorNotRunning = processorNotRunning;
            this.closedBursts = closedBursts;
            this.mapped = mapped;
            this.unmapped = unmapped;
            this.duplicates = duplicates;
        }

        public long observations() {
            return observations;
        }

        public long observationQueueFull() {
            return observationQueueFull;
        }

        public long processorNotRunning() {
            return processorNotRunning;
        }

        public long closedBursts() {
            return closedBursts;
        }

        public long mapped() {
            return mapped;
        }

        public long unmapped() {
            return unmapped;
        }

        public long duplicates() {
            return duplicates;
        }

    }
}
