package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

/**
 * Component-owned engineering counters for the LogBook commit/event path.
 *
 * <p>All writes occur on the Data Lane, so recording uses simple
 * primitive fields. Fields are volatile only so an engineering reader on
 * another thread can take a safe non-transactional snapshot without locking the
 * registration path.</p>
 */
public final class LogBookMetrics {
    private volatile long timingDataAppendAttempts;
    private volatile long timingDataAppendFailures;
    private volatile long timingDataCommitCount;
    private volatile long totalTimingDataAppendNanos;
    private volatile long maxTimingDataAppendNanos;
    private volatile long timingDataEventDeliveries;
    private volatile long timingDataEventListenerFailures;
    private volatile long totalTimingDataEventNanos;
    private volatile long maxTimingDataEventNanos;

    /** Records one durable TimingData append attempt. */
    void recordAppendAttempt() {
        timingDataAppendAttempts++;
    }

    /** Records one failed durable TimingData append. */
    void recordAppendFailure() {
        timingDataAppendFailures++;
    }

    /** Records one TimingData record committed to LogBook state. */
    void recordCommit() {
        timingDataCommitCount++;
    }

    /** Records non-negative append latency and updates its maximum. */
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

    /** Records one TimingData event delivery, including listener failures and latency. */
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

    /** Returns an immutable pull-based snapshot of the current counters. */
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

        /** Creates one immutable engineering snapshot from current LogBook counters. */
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

        /** Returns durable append attempts. */
        public long timingDataAppendAttempts() {
            return timingDataAppendAttempts;
        }

        /** Returns failed durable append attempts. */
        public long timingDataAppendFailures() {
            return timingDataAppendFailures;
        }

        /** Returns successfully committed TimingData records. */
        public long timingDataCommitCount() {
            return timingDataCommitCount;
        }

        /** Returns cumulative durable append latency in nanoseconds. */
        public long totalTimingDataAppendNanos() {
            return totalTimingDataAppendNanos;
        }

        /** Returns maximum observed durable append latency in nanoseconds. */
        public long maxTimingDataAppendNanos() {
            return maxTimingDataAppendNanos;
        }

        /** Returns TimingData event delivery attempts. */
        public long timingDataEventDeliveries() {
            return timingDataEventDeliveries;
        }

        /** Returns listener failures observed during TimingData event delivery. */
        public long timingDataEventListenerFailures() {
            return timingDataEventListenerFailures;
        }

        /** Returns cumulative TimingData event delivery latency in nanoseconds. */
        public long totalTimingDataEventNanos() {
            return totalTimingDataEventNanos;
        }

        /** Returns maximum observed TimingData event delivery latency in nanoseconds. */
        public long maxTimingDataEventNanos() {
            return maxTimingDataEventNanos;
        }
    }
}
