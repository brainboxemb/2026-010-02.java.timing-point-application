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

    /** Records one observation accepted by TagProcessor ingress. */
    void recordObservation() {
        observationCount.incrementAndGet();
    }

    /** Records one observation rejected because the bounded queue was full. */
    void recordObservationQueueFull() {
        observationQueueFullCount.incrementAndGet();
    }

    /** Records one observation rejected because TagProcessor was not running. */
    void recordProcessorNotRunning() {
        processorNotRunningCount.incrementAndGet();
    }

    /** Records one passage burst closed by filter policy. */
    void recordClosedBurst() {
        closedBurstCount.incrementAndGet();
    }

    /** Records one observation mapped to a RegistrationId. */
    void recordMapped() {
        mappedCount.incrementAndGet();
    }

    /** Records one observation without a RegistrationId mapping. */
    void recordUnmapped() {
        unmappedCount.incrementAndGet();
    }

    /** Records one selected registration suppressed by the duplicate window. */
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

        /** Creates one immutable metric snapshot from current counters. */
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

        /** Returns accepted observation ingress count. */
        public long observations() {
            return observations;
        }

        /** Returns observation rejections caused by a full queue. */
        public long observationQueueFull() {
            return observationQueueFull;
        }

        /** Returns observation rejections while TagProcessor was not running. */
        public long processorNotRunning() {
            return processorNotRunning;
        }

        /** Returns the number of passage bursts closed by filter policy. */
        public long closedBursts() {
            return closedBursts;
        }

        /** Returns observations successfully mapped to registrations. */
        public long mapped() {
            return mapped;
        }

        /** Returns observations without a registration mapping. */
        public long unmapped() {
            return unmapped;
        }

        /** Returns selected registrations suppressed as duplicates. */
        public long duplicates() {
            return duplicates;
        }

    }
}
