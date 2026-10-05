package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;

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
    private final AtomicLong admittedCount = new AtomicLong();
    private final AtomicLong queueFullCount = new AtomicLong();
    private final AtomicLong nodeNotRunningCount = new AtomicLong();

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

    public Snapshot snapshot() {
        return new Snapshot(
                observationCount.get(),
                observationQueueFullCount.get(),
                processorNotRunningCount.get(),
                closedBurstCount.get(),
                mappedCount.get(),
                unmappedCount.get(),
                duplicateCount.get(),
                admittedCount.get(),
                queueFullCount.get(),
                nodeNotRunningCount.get());
    }

    public static final class Snapshot {
        private final long observations;
        private final long observationQueueFull;
        private final long processorNotRunning;
        private final long closedBursts;
        private final long mapped;
        private final long unmapped;
        private final long duplicates;
        private final long admitted;
        private final long queueFull;
        private final long nodeNotRunning;

        private Snapshot(
                long observations,
                long observationQueueFull,
                long processorNotRunning,
                long closedBursts,
                long mapped,
                long unmapped,
                long duplicates,
                long admitted,
                long queueFull,
                long nodeNotRunning) {
            this.observations = observations;
            this.observationQueueFull = observationQueueFull;
            this.processorNotRunning = processorNotRunning;
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
