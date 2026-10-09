package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

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
     * Immediate result of admission-only command ingress through TimingNode.offer(...).
     *
     * <p>This is deliberately not the later domain result.</p>
     */
    public enum CommandAdmission {
        ACCEPTED,
        FULL,
        NOT_RUNNING
    }

    public enum State {
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
        private final State state;
        private final LocationId locationId;
        private final boolean timingDataTailRecovered;
        private final List<Problem> problems;

        Status(
                NodeId timingNodeId,
                State state,
                LocationId locationId,
                boolean timingDataTailRecovered) {
            this(
                    timingNodeId,
                    state,
                    locationId,
                    timingDataTailRecovered,
                    Collections.<Problem>emptyList());
        }

        Status(
                NodeId timingNodeId,
                State state,
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
            this.state = state;
            this.locationId = locationId;
            this.timingDataTailRecovered = timingDataTailRecovered;
            this.problems = Collections.unmodifiableList(
                    new ArrayList<Problem>(problems));
        }

        public NodeId timingNodeId() {
            return timingNodeId;
        }

        public State state() {
            return state;
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
