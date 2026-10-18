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

    private volatile int passagePoolInitialSize;
    private volatile int passagePoolSize;
    private volatile int passagePoolInUse;
    private volatile int passagePoolPeakInUse;

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

    /** Records one passage closed by filter policy. */
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

    /** Publishes the current reusable RegistrationPassage pool state. */
    void recordPassagePool(
            int initialSize,
            int size,
            int inUse,
            int peakInUse) {
        passagePoolInitialSize = initialSize;
        passagePoolSize = size;
        passagePoolInUse = inUse;
        passagePoolPeakInUse = peakInUse;
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
                duplicateCount.get(),
                passagePoolInitialSize,
                passagePoolSize,
                passagePoolInUse,
                passagePoolPeakInUse);
    }

    /** Immutable pull-based view of the current TagProcessor counters. */
    public static final class Snapshot {
        private final long observations;
        private final long observationQueueFull;
        private final long processorNotRunning;
        private final long closedBursts;
        private final long mapped;
        private final long unmapped;
        private final long duplicates;
        private final int passagePoolInitialSize;
        private final int passagePoolSize;
        private final int passagePoolInUse;
        private final int passagePoolPeakInUse;

        /** Creates one immutable metric snapshot from current counters. */
        private Snapshot(
                long observations,
                long observationQueueFull,
                long processorNotRunning,
                long closedBursts,
                long mapped,
                long unmapped,
                long duplicates,
                int passagePoolInitialSize,
                int passagePoolSize,
                int passagePoolInUse,
                int passagePoolPeakInUse) {
            this.observations = observations;
            this.observationQueueFull = observationQueueFull;
            this.processorNotRunning = processorNotRunning;
            this.closedBursts = closedBursts;
            this.mapped = mapped;
            this.unmapped = unmapped;
            this.duplicates = duplicates;
            this.passagePoolInitialSize = passagePoolInitialSize;
            this.passagePoolSize = passagePoolSize;
            this.passagePoolInUse = passagePoolInUse;
            this.passagePoolPeakInUse = passagePoolPeakInUse;
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

        /** Returns the RegistrationPassage objects created during pool initialization. */
        public int passagePoolInitialSize() {
            return passagePoolInitialSize;
        }

        /** Returns the total RegistrationPassage objects created so far. */
        public int passagePoolSize() {
            return passagePoolSize;
        }

        /** Returns RegistrationPassage objects currently checked out of the pool. */
        public int passagePoolInUse() {
            return passagePoolInUse;
        }

        /** Returns the highest simultaneous RegistrationPassage usage observed. */
        public int passagePoolPeakInUse() {
            return passagePoolPeakInUse;
        }
    }
}
