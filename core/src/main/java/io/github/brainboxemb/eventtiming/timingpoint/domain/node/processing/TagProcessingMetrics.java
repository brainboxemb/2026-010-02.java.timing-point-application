package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Component-owned runtime metrics for the tag-processing path.
 *
 * <p>Hot-path record methods update only primitive counters. Snapshot creation
 * is explicit and may allocate; recording never creates diagnostic objects.</p>
 */
public final class TagProcessingMetrics {
    private final AtomicLong observationCount = new AtomicLong();
    private final AtomicLong observationQueueFullCount = new AtomicLong();
    private final AtomicLong processorNotRunningCount = new AtomicLong();
    private final AtomicLong closedPassageCount = new AtomicLong();
    private final AtomicLong mappedCount = new AtomicLong();
    private final AtomicLong unmappedCount = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();

    private volatile int passagePoolInitialSize;
    private volatile int passagePoolSize;
    private volatile int passagePoolInUse;
    private volatile int passagePoolPeakInUse;

    private volatile int duplicateTableInitialCapacity;
    private volatile int duplicateTableCapacity;
    private volatile int duplicateTableSize;
    private volatile int duplicateTablePeakSize;

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

    /** Records one passage closed because a passage boundary or idle flush was reached. */
    void recordClosedPassage() {
        closedPassageCount.incrementAndGet();
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

    /** Publishes current reusable RegistrationPassage-pool occupancy without allocating a snapshot. */
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

    /** Publishes the primitive duplicate table's current capacity and occupancy. */
    void recordDuplicateTable(
            int initialCapacity,
            int capacity,
            int size,
            int peakSize) {
        duplicateTableInitialCapacity = initialCapacity;
        duplicateTableCapacity = capacity;
        duplicateTableSize = size;
        duplicateTablePeakSize = peakSize;
    }

    /** Returns one immutable pull-based view of current processing metrics. */
    public Snapshot snapshot() {
        return new Snapshot(
                observationCount.get(),
                observationQueueFullCount.get(),
                processorNotRunningCount.get(),
                closedPassageCount.get(),
                mappedCount.get(),
                unmappedCount.get(),
                duplicateCount.get(),
                passagePoolInitialSize,
                passagePoolSize,
                passagePoolInUse,
                passagePoolPeakInUse,
                duplicateTableInitialCapacity,
                duplicateTableCapacity,
                duplicateTableSize,
                duplicateTablePeakSize);
    }

    /** Immutable pull-based view of the current TagProcessor counters. */
    public static final class Snapshot {
        private final long observations;
        private final long observationQueueFull;
        private final long processorNotRunning;
        private final long closedPassages;
        private final long mapped;
        private final long unmapped;
        private final long duplicates;
        private final int passagePoolInitialSize;
        private final int passagePoolSize;
        private final int passagePoolInUse;
        private final int passagePoolPeakInUse;
        private final int duplicateTableInitialCapacity;
        private final int duplicateTableCapacity;
        private final int duplicateTableSize;
        private final int duplicateTablePeakSize;

        private Snapshot(
                long observations,
                long observationQueueFull,
                long processorNotRunning,
                long closedPassages,
                long mapped,
                long unmapped,
                long duplicates,
                int passagePoolInitialSize,
                int passagePoolSize,
                int passagePoolInUse,
                int passagePoolPeakInUse,
                int duplicateTableInitialCapacity,
                int duplicateTableCapacity,
                int duplicateTableSize,
                int duplicateTablePeakSize) {
            this.observations = observations;
            this.observationQueueFull = observationQueueFull;
            this.processorNotRunning = processorNotRunning;
            this.closedPassages = closedPassages;
            this.mapped = mapped;
            this.unmapped = unmapped;
            this.duplicates = duplicates;
            this.passagePoolInitialSize = passagePoolInitialSize;
            this.passagePoolSize = passagePoolSize;
            this.passagePoolInUse = passagePoolInUse;
            this.passagePoolPeakInUse = passagePoolPeakInUse;
            this.duplicateTableInitialCapacity = duplicateTableInitialCapacity;
            this.duplicateTableCapacity = duplicateTableCapacity;
            this.duplicateTableSize = duplicateTableSize;
            this.duplicateTablePeakSize = duplicateTablePeakSize;
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

        public long closedPassages() {
            return closedPassages;
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

        public int passagePoolInitialSize() {
            return passagePoolInitialSize;
        }

        public int passagePoolSize() {
            return passagePoolSize;
        }

        public int passagePoolInUse() {
            return passagePoolInUse;
        }

        public int passagePoolPeakInUse() {
            return passagePoolPeakInUse;
        }

        public int duplicateTableInitialCapacity() {
            return duplicateTableInitialCapacity;
        }

        public int duplicateTableCapacity() {
            return duplicateTableCapacity;
        }

        public int duplicateTableSize() {
            return duplicateTableSize;
        }

        public int duplicateTablePeakSize() {
            return duplicateTablePeakSize;
        }
    }
}
