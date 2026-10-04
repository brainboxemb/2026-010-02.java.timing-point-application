package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-allocation counters for the tag-processing path.
 *
 * <p>The hot path only increments AtomicLong values. Creating a Snapshot is a
 * pull operation for tests/engineering measurement and is not done for every
 * antenna observation or registration.</p>
 */
public final class TagProcessingCounters {
    private final AtomicLong observationCount = new AtomicLong();
    private final AtomicLong closedBurstCount = new AtomicLong();
    private final AtomicLong mappedCount = new AtomicLong();
    private final AtomicLong unmappedCount = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();
    private final AtomicLong admittedCount = new AtomicLong();
    private final AtomicLong queueFullCount = new AtomicLong();
    private final AtomicLong nodeNotRunningCount = new AtomicLong();

    void recordObservation() {
        observationCount.incrementAndGet();
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

    void recordAdmission(CommandAdmission admission) {
        switch (admission) {
            case ACCEPTED:
                admittedCount.incrementAndGet();
                break;
            case FULL:
                queueFullCount.incrementAndGet();
                break;
            case NOT_RUNNING:
                nodeNotRunningCount.incrementAndGet();
                break;
            default:
                throw new IllegalStateException(
                        "Unsupported TimingNode admission result " + admission);
        }
    }

    /** Returns one immutable diagnostic view of the current cumulative counters. */
    public Snapshot snapshot() {
        return new Snapshot(
                observationCount.get(),
                closedBurstCount.get(),
                mappedCount.get(),
                unmappedCount.get(),
                duplicateCount.get(),
                admittedCount.get(),
                queueFullCount.get(),
                nodeNotRunningCount.get());
    }

    /** Immutable cumulative tag-processing counter snapshot. */
    public static final class Snapshot {
        private final long observations;
        private final long closedBursts;
        private final long mapped;
        private final long unmapped;
        private final long duplicates;
        private final long admitted;
        private final long queueFull;
        private final long nodeNotRunning;

        private Snapshot(
                long observations,
                long closedBursts,
                long mapped,
                long unmapped,
                long duplicates,
                long admitted,
                long queueFull,
                long nodeNotRunning) {
            this.observations = observations;
            this.closedBursts = closedBursts;
            this.mapped = mapped;
            this.unmapped = unmapped;
            this.duplicates = duplicates;
            this.admitted = admitted;
            this.queueFull = queueFull;
            this.nodeNotRunning = nodeNotRunning;
        }

        public long observations() {
            return observations;
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

        public long admitted() {
            return admitted;
        }

        public long queueFull() {
            return queueFull;
        }

        public long nodeNotRunning() {
            return nodeNotRunning;
        }
    }
}
