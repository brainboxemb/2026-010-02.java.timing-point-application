package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

/**
 * Component-owned engineering counters for the TimingNode commit/event path.
 *
 * <p>All writes occur on the TimingNode serial lane, so recording uses simple
 * primitive fields. Fields are volatile only so an engineering reader on
 * another thread can take a safe non-transactional snapshot without locking the
 * registration path.</p>
 */
public final class TimingNodeMetrics {
    private volatile long timingDataAppendAttempts;
    private volatile long timingDataAppendFailures;
    private volatile long timingDataCommitCount;
    private volatile long totalTimingDataAppendNanos;
    private volatile long maxTimingDataAppendNanos;
    private volatile long timingDataEventDeliveries;
    private volatile long timingDataEventListenerFailures;
    private volatile long totalTimingDataEventNanos;
    private volatile long maxTimingDataEventNanos;

    void recordAppendAttempt() {
        timingDataAppendAttempts++;
    }

    void recordAppendFailure() {
        timingDataAppendFailures++;
    }

    void recordCommit() {
        timingDataCommitCount++;
    }

    void recordAppendDuration(
            long elapsedNanos) {
        long safeElapsed =
                Math.max(
                        0L,
                        elapsedNanos);
        totalTimingDataAppendNanos += safeElapsed;
        maxTimingDataAppendNanos =
                Math.max(
                        maxTimingDataAppendNanos,
                        safeElapsed);
    }

    void recordEventDelivery(
            long elapsedNanos,
            int listenerFailures) {
        long safeElapsed =
                Math.max(
                        0L,
                        elapsedNanos);
        timingDataEventDeliveries++;
        timingDataEventListenerFailures += listenerFailures;
        totalTimingDataEventNanos += safeElapsed;
        maxTimingDataEventNanos =
                Math.max(
                        maxTimingDataEventNanos,
                        safeElapsed);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                timingDataAppendAttempts,
                timingDataAppendFailures,
                timingDataCommitCount,
                totalTimingDataAppendNanos,
                maxTimingDataAppendNanos,
                timingDataEventDeliveries,
                timingDataEventListenerFailures,
                totalTimingDataEventNanos,
                maxTimingDataEventNanos);
    }

    /** Immutable pull-based view for engineering characterization. */
    public static final class Snapshot {
        private final long timingDataAppendAttempts;
        private final long timingDataAppendFailures;
        private final long timingDataCommitCount;
        private final long totalTimingDataAppendNanos;
        private final long maxTimingDataAppendNanos;
        private final long timingDataEventDeliveries;
        private final long timingDataEventListenerFailures;
        private final long totalTimingDataEventNanos;
        private final long maxTimingDataEventNanos;

        private Snapshot(
                long timingDataAppendAttempts,
                long timingDataAppendFailures,
                long timingDataCommitCount,
                long totalTimingDataAppendNanos,
                long maxTimingDataAppendNanos,
                long timingDataEventDeliveries,
                long timingDataEventListenerFailures,
                long totalTimingDataEventNanos,
                long maxTimingDataEventNanos) {
            this.timingDataAppendAttempts = timingDataAppendAttempts;
            this.timingDataAppendFailures = timingDataAppendFailures;
            this.timingDataCommitCount = timingDataCommitCount;
            this.totalTimingDataAppendNanos = totalTimingDataAppendNanos;
            this.maxTimingDataAppendNanos = maxTimingDataAppendNanos;
            this.timingDataEventDeliveries = timingDataEventDeliveries;
            this.timingDataEventListenerFailures = timingDataEventListenerFailures;
            this.totalTimingDataEventNanos = totalTimingDataEventNanos;
            this.maxTimingDataEventNanos = maxTimingDataEventNanos;
        }

        public long timingDataAppendAttempts() {
            return timingDataAppendAttempts;
        }

        public long timingDataAppendFailures() {
            return timingDataAppendFailures;
        }

        public long timingDataCommitCount() {
            return timingDataCommitCount;
        }

        public long totalTimingDataAppendNanos() {
            return totalTimingDataAppendNanos;
        }

        public long maxTimingDataAppendNanos() {
            return maxTimingDataAppendNanos;
        }

        public long timingDataEventDeliveries() {
            return timingDataEventDeliveries;
        }

        public long timingDataEventListenerFailures() {
            return timingDataEventListenerFailures;
        }

        public long totalTimingDataEventNanos() {
            return totalTimingDataEventNanos;
        }

        public long maxTimingDataEventNanos() {
            return maxTimingDataEventNanos;
        }
    }
}
