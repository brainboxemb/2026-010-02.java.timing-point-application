package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Public value/result types used by the TimingNode component boundary.
 *
 * <p>This class is only a Java source-code grouping. It has no runtime state,
 * lifecycle or architecture responsibility of its own.</p>
 */
public final class TimingNodeTypes {
    private TimingNodeTypes() {
    }

    /**
     * Immediate result of submission-only command ingress.
     *
     * <p>This is deliberately not the later domain result.</p>
     */
    public enum CommandAdmission {
        ACCEPTED,
        FULL,
        NOT_RUNNING
    }

    public enum Lifecycle {
        CLOSED,
        OPEN,
        ERROR
    }

    public enum ProblemSeverity {
        WARNING,
        ERROR
    }

    public enum ProblemCode {
        TIMING_DATA_RECOVERY_FAILED
    }

    public static final class Problem {
        private final ProblemCode code;
        private final ProblemSeverity severity;
        private final String message;

        Problem(
                ProblemCode code,
                ProblemSeverity severity,
                String message) {
            if (code == null) {
                throw new IllegalArgumentException("code must not be null");
            }
            if (severity == null) {
                throw new IllegalArgumentException("severity must not be null");
            }
            if (message == null || message.trim().isEmpty()) {
                throw new IllegalArgumentException("message must not be blank");
            }
            this.code = code;
            this.severity = severity;
            this.message = message.trim();
        }

        public ProblemCode code() {
            return code;
        }

        public ProblemSeverity severity() {
            return severity;
        }

        public String message() {
            return message;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Problem)) {
                return false;
            }
            Problem that = (Problem) other;
            return code == that.code
                    && severity == that.severity
                    && message.equals(that.message);
        }

        @Override
        public int hashCode() {
            int result = code.hashCode();
            result = 31 * result + severity.hashCode();
            result = 31 * result + message.hashCode();
            return result;
        }
    }

    public enum OpenResult {
        OPENED,
        ALREADY_OPEN
    }

    public enum CloseResult {
        CLOSED,
        ALREADY_CLOSED
    }


    public static final class RegistrationResult {
        public enum Outcome {
            COMMITTED,
            NODE_NOT_OPEN
        }

        private final Outcome outcome;
        private final TimingData timingData;

        private RegistrationResult(Outcome outcome, TimingData timingData) {
            this.outcome = outcome;
            this.timingData = timingData;
        }

        static RegistrationResult committed(TimingData timingData) {
            return new RegistrationResult(Outcome.COMMITTED, timingData);
        }

        static RegistrationResult nodeNotOpen() {
            return new RegistrationResult(Outcome.NODE_NOT_OPEN, null);
        }

        public Outcome outcome() {
            return outcome;
        }

        public boolean committed() {
            return outcome == Outcome.COMMITTED;
        }

        public TimingData timingData() {
            if (timingData == null) {
                throw new IllegalStateException(
                        "registration did not commit TimingData");
            }
            return timingData;
        }
    }

    public static final class Status {
        private final NodeId timingNodeId;
        private final Lifecycle lifecycle;
        private final LocationId locationId;
        private final boolean timingDataTailRecovered;
        private final List<Problem> problems;

        Status(
                NodeId timingNodeId,
                Lifecycle lifecycle,
                LocationId locationId,
                boolean timingDataTailRecovered) {
            this(
                    timingNodeId,
                    lifecycle,
                    locationId,
                    timingDataTailRecovered,
                    Collections.<Problem>emptyList());
        }

        Status(
                NodeId timingNodeId,
                Lifecycle lifecycle,
                LocationId locationId,
                boolean timingDataTailRecovered,
                List<Problem> problems) {
            if (problems == null) {
                throw new IllegalArgumentException("problems must not be null");
            }
            for (Problem problem : problems) {
                if (problem == null) {
                    throw new IllegalArgumentException("problem must not be null");
                }
            }
            this.timingNodeId = timingNodeId;
            this.lifecycle = lifecycle;
            this.locationId = locationId;
            this.timingDataTailRecovered = timingDataTailRecovered;
            this.problems = Collections.unmodifiableList(
                    new ArrayList<Problem>(problems));
        }

        public NodeId timingNodeId() {
            return timingNodeId;
        }

        public Lifecycle lifecycle() {
            return lifecycle;
        }

        public boolean hasLocation() {
            return locationId != null;
        }

        public LocationId locationId() {
            return locationId;
        }

        public boolean timingDataTailRecovered() {
            return timingDataTailRecovered;
        }

        public List<Problem> problems() {
            return problems;
        }

        public boolean hasProblems() {
            return !problems.isEmpty();
        }
    }

    /**
     * Pull-based engineering snapshot of one TimingNode runtime.
     *
     * <p>The hot path stores only primitive counters/timestamps. Constructing
     * this snapshot is an explicit diagnostic action and is not performed for
     * each registration.</p>
     */
    public static final class RuntimeMetrics {
        private final int queueDepth;
        private final int queueHighWaterMark;
        private final long queueAcceptedCount;
        private final long queueFullCount;
        private final long queueNotRunningCount;
        private final long queueCompletedCount;
        private final long totalQueueWaitNanos;
        private final long maxQueueWaitNanos;
        private final long totalExecutionNanos;
        private final long maxExecutionNanos;
        private final long timingDataAppendAttempts;
        private final long timingDataAppendFailures;
        private final long timingDataCommitCount;
        private final long totalTimingDataAppendNanos;
        private final long maxTimingDataAppendNanos;
        private final long timingDataEventDeliveries;
        private final long timingDataEventListenerFailures;
        private final long totalTimingDataEventNanos;
        private final long maxTimingDataEventNanos;
        private final long workerThreadCpuTimeNanos;

        RuntimeMetrics(
                int queueDepth,
                int queueHighWaterMark,
                long queueAcceptedCount,
                long queueFullCount,
                long queueNotRunningCount,
                long queueCompletedCount,
                long totalQueueWaitNanos,
                long maxQueueWaitNanos,
                long totalExecutionNanos,
                long maxExecutionNanos,
                long timingDataAppendAttempts,
                long timingDataAppendFailures,
                long timingDataCommitCount,
                long totalTimingDataAppendNanos,
                long maxTimingDataAppendNanos,
                long timingDataEventDeliveries,
                long timingDataEventListenerFailures,
                long totalTimingDataEventNanos,
                long maxTimingDataEventNanos,
                long workerThreadCpuTimeNanos) {
            this.queueDepth = queueDepth;
            this.queueHighWaterMark = queueHighWaterMark;
            this.queueAcceptedCount = queueAcceptedCount;
            this.queueFullCount = queueFullCount;
            this.queueNotRunningCount = queueNotRunningCount;
            this.queueCompletedCount = queueCompletedCount;
            this.totalQueueWaitNanos = totalQueueWaitNanos;
            this.maxQueueWaitNanos = maxQueueWaitNanos;
            this.totalExecutionNanos = totalExecutionNanos;
            this.maxExecutionNanos = maxExecutionNanos;
            this.timingDataAppendAttempts = timingDataAppendAttempts;
            this.timingDataAppendFailures = timingDataAppendFailures;
            this.timingDataCommitCount = timingDataCommitCount;
            this.totalTimingDataAppendNanos = totalTimingDataAppendNanos;
            this.maxTimingDataAppendNanos = maxTimingDataAppendNanos;
            this.timingDataEventDeliveries = timingDataEventDeliveries;
            this.timingDataEventListenerFailures = timingDataEventListenerFailures;
            this.totalTimingDataEventNanos = totalTimingDataEventNanos;
            this.maxTimingDataEventNanos = maxTimingDataEventNanos;
            this.workerThreadCpuTimeNanos = workerThreadCpuTimeNanos;
        }

        public int queueDepth() {
            return queueDepth;
        }

        public int queueHighWaterMark() {
            return queueHighWaterMark;
        }

        public long queueAcceptedCount() {
            return queueAcceptedCount;
        }

        public long queueFullCount() {
            return queueFullCount;
        }

        public long queueNotRunningCount() {
            return queueNotRunningCount;
        }

        public long queueCompletedCount() {
            return queueCompletedCount;
        }

        public long totalQueueWaitNanos() {
            return totalQueueWaitNanos;
        }

        public long maxQueueWaitNanos() {
            return maxQueueWaitNanos;
        }

        public long totalExecutionNanos() {
            return totalExecutionNanos;
        }

        public long maxExecutionNanos() {
            return maxExecutionNanos;
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

        public long workerThreadCpuTimeNanos() {
            return workerThreadCpuTimeNanos;
        }
    }

    public static final class StartupException extends RuntimeException {
        StartupException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class OperationException extends RuntimeException {
        public enum Reason {
            BUSY,
            UNAVAILABLE,
            FAILED,
            INTERRUPTED,
            TIMEOUT
        }

        private final Reason reason;

        OperationException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        OperationException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
