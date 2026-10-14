package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Public value/result types used by the TimingNode component boundary.
 *
 * <p>This class is only a Java source-code grouping. It has no runtime state,
 * lifecycle or architecture responsibility of its own.</p>
 */
public final class TimingNodeTypes {
    private TimingNodeTypes() {
    }

    /** Logical operating state published by the LogBook. */
    public enum State {
        /** No timing location is currently open. */
        CLOSED,

        /** A timing location is open for timing operations. */
        OPEN,

        /** A reported LogBook problem prevents normal state from being represented. */
        ERROR
    }

    /** Severity attached to a machine-readable node problem. */
    public enum ProblemSeverity {
        WARNING,
        ERROR
    }

    /**
     * Stable machine-readable problem categories exposed through node status.
     */
    public enum ProblemCode {
        /** Existing persisted TimingData could not be loaded or validated at startup. */
        TIMING_DATA_LOAD_FAILED
    }

    /**
     * Immutable problem entry included in a published node status.
     *
     * <p>The code is intended for programmatic handling; the message is
     * diagnostic text for operators and logs.</p>
     */
    public static final class Problem {
        private final ProblemCode code;
        private final ProblemSeverity severity;
        private final String message;

        public Problem(
                ProblemCode code,
                ProblemSeverity severity,
                String message) {
            checkArgument(code != null, "code must not be null");
            checkArgument(severity != null, "severity must not be null");
            checkArgument(
                    message != null && !message.trim().isEmpty(),
                    "message must not be blank");
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

    /** Result of the idempotent LogBook open command. */
    public enum OpenResult {
        /** The command changed the LogBook to OPEN. */
        OPENED,

        /** The LogBook was already open; no second open transition occurred. */
        ALREADY_OPEN
    }

    /** Result of the idempotent LogBook close command. */
    public enum CloseResult {
        /** The command changed the LogBook to CLOSED. */
        CLOSED,

        /** The LogBook was already closed; no second close transition occurred. */
        ALREADY_CLOSED
    }

    /**
     * Domain result of an automatic or manual registration command.
     *
     * <p>A committed result contains the immutable TimingData record that was
     * durably committed. A rejected result contains no TimingData.</p>
     */
    public static final class RegistrationResult {
        /** Why the registration command did or did not produce TimingData. */
        public enum Outcome {
            /** The registration was durably committed as TimingData. */
            COMMITTED,

            /** No registration was committed because the node is not open. */
            NODE_NOT_OPEN
        }

        private final Outcome outcome;
        private final TimingData timingData;

        private RegistrationResult(Outcome outcome, TimingData timingData) {
            this.outcome = outcome;
            this.timingData = timingData;
        }

        public static RegistrationResult committed(TimingData timingData) {
            return new RegistrationResult(Outcome.COMMITTED, timingData);
        }

        public static RegistrationResult nodeNotOpen() {
            return new RegistrationResult(Outcome.NODE_NOT_OPEN, null);
        }

        public Outcome outcome() {
            return outcome;
        }

        public boolean committed() {
            return outcome == Outcome.COMMITTED;
        }

        /**
         * Returns the committed TimingData.
         *
         * @throws IllegalStateException when {@link #outcome()} is not
         *         {@link Outcome#COMMITTED}
         */
        public TimingData timingData() {
            if (timingData == null) {
                throw new IllegalStateException(
                        "registration did not commit TimingData");
            }
            return timingData;
        }
    }

    /**
     * Immutable status snapshot published by the LogBook.
     *
     * <p>The snapshot combines logical OPEN/CLOSED/ERROR state, the optional
     * active location, startup tail-repair information and immutable problem
     * entries. It is safe to expose outside the Data Lane.</p>
     */
    public static final class Status {
        private final NodeId timingNodeId;
        private final State state;
        private final LocationId locationId;
        private final boolean timingDataTailRepaired;
        private final List<Problem> problems;

        public Status(
                NodeId timingNodeId,
                State state,
                LocationId locationId,
                boolean timingDataTailRepaired) {
            this(
                    timingNodeId,
                    state,
                    locationId,
                    timingDataTailRepaired,
                    Collections.<Problem>emptyList());
        }

        public Status(
                NodeId timingNodeId,
                State state,
                LocationId locationId,
                boolean timingDataTailRepaired,
                List<Problem> problems) {
            checkArgument(timingNodeId != null, "timingNodeId must not be null");
            checkArgument(state != null, "state must not be null");
            checkArgument(problems != null, "problems must not be null");
            for (Problem problem : problems) {
                checkArgument(problem != null, "problem must not be null");
            }
            this.timingNodeId = timingNodeId;
            this.state = state;
            this.locationId = locationId;
            this.timingDataTailRepaired = timingDataTailRepaired;
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

        /**
         * Returns the active location, or {@code null} when no location is
         * present. Callers may use {@link #hasLocation()} before reading it.
         */
        public LocationId locationId() {
            return locationId;
        }

        public boolean timingDataTailRepaired() {
            return timingDataTailRepaired;
        }

        public List<Problem> problems() {
            return problems;
        }

        public boolean hasProblems() {
            return !problems.isEmpty();
        }
    }

    /**
     * Signals that node startup failed before normal command handling became
     * available.
     */
    public static final class StartupException extends RuntimeException {
        StartupException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Caller-visible failure while executing a node operation.
     *
     * <p>The reason is a stable category for application/presentation handling;
     * the exception message and cause provide diagnostics.</p>
     */
    public static class OperationException extends RuntimeException {
        /** Stable reason why an operation could not return its normal result. */
        public enum Reason {
            /** The bounded owner-lane queue could not accept more work. */
            BUSY,

            /** The required component/state is not currently available. */
            UNAVAILABLE,

            /** Execution or its owner lane failed. */
            FAILED,

            /** The caller thread was interrupted while waiting for a result. */
            INTERRUPTED,

            /**
             * Waiting exceeded the operation timeout; the final operation
             * outcome may be unknown to the caller.
             */
            TIMEOUT
        }

        private final Reason reason;

        public OperationException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public OperationException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
