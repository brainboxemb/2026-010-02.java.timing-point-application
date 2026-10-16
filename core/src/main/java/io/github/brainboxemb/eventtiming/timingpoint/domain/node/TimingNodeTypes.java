package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * Public value/result types used by the TimingNode component boundary.
 *
 * <p>This class is only a Java source-code grouping. It has no runtime state,
 * lifecycle or architecture responsibility of its own.</p>
 */
public final class TimingNodeTypes {
    /** Prevents instantiation of this source-code grouping class. */
    private TimingNodeTypes() {
    }

    /** OPEN/CLOSED state of a TimingNode. */
    public enum State {
        /** No timing location is currently open. */
        CLOSED,

        /** A timing location is open for timing operations. */
        OPEN
    }

    /** Status returned by TimingNode: either {@link Status} or {@link ErrorStatus}. */
    public interface NodeStatus {
        /** Returns the node ID. */
        NodeId timingNodeId();
    }

    /** Error categories reported by {@link ErrorStatus}. */
    public enum ErrorCode {
        /** Existing persisted TimingData could not be loaded or validated at startup. */
        TIMING_DATA_LOAD_FAILED
    }

    /** TimingNode failure status. */
    public static final class ErrorStatus implements NodeStatus {
        private final NodeId timingNodeId;
        private final ErrorCode code;
        private final String message;

        public ErrorStatus(NodeId timingNodeId, ErrorCode code, String message) {
            checkArgument(timingNodeId != null, "timingNodeId must not be null");
            checkArgument(code != null, "code must not be null");
            checkArgument(message != null && !message.trim().isEmpty(), "message must not be blank");
            this.timingNodeId = timingNodeId;
            this.code = code;
            this.message = message.trim();
        }

        @Override
        public NodeId timingNodeId() {
            return timingNodeId;
        }

        public ErrorCode code() {
            return code;
        }

        public String message() {
            return message;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ErrorStatus)) return false;
            ErrorStatus that = (ErrorStatus) other;
            return timingNodeId.equals(that.timingNodeId)
                    && code == that.code
                    && message.equals(that.message);
        }

        @Override
        public int hashCode() {
            int result = timingNodeId.hashCode();
            result = 31 * result + code.hashCode();
            result = 31 * result + message.hashCode();
            return result;
        }
    }

    /** Result of the idempotent TimingNode open operation. */
    public enum OpenResult {
        /** The operation changed the TimingNode to OPEN. */
        OPENED,

        /** The TimingNode was already open; no second transition occurred. */
        ALREADY_OPEN
    }

    /** Result of the idempotent TimingNode close operation. */
    public enum CloseResult {
        /** The operation changed the TimingNode to CLOSED. */
        CLOSED,

        /** The TimingNode was already closed; no second transition occurred. */
        ALREADY_CLOSED
    }

    /**
     * Domain result of an automatic or manual registration command.
     *
     * <p>A committed result contains the TimingData record that was
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

        /** Creates one registration result. */
        private RegistrationResult(Outcome outcome, TimingData timingData) {
            this.outcome = outcome;
            this.timingData = timingData;
        }

        /** Creates a successful result carrying the committed TimingData record. */
        public static RegistrationResult committed(TimingData timingData) {
            return new RegistrationResult(Outcome.COMMITTED, timingData);
        }

        /** Creates a result for a registration rejected because the node is not open. */
        public static RegistrationResult nodeNotOpen() {
            return new RegistrationResult(Outcome.NODE_NOT_OPEN, null);
        }

        /** Returns the registration outcome. */
        public Outcome outcome() {
            return outcome;
        }

        /** Reports whether this result contains a committed TimingData record. */
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
            checkState(timingData != null, "registration did not commit TimingData");
            return timingData;
        }
    }

    /**
     * OPEN/CLOSED status reported by TimingNode.
     *
     * <p>Error information is represented separately by {@link ErrorStatus};
     * this value can therefore only represent OPEN or CLOSED node state.</p>
     */
    public static final class Status implements NodeStatus {
        private final NodeId timingNodeId;
        private final State state;
        private final LocationId locationId;

        public Status(NodeId timingNodeId, State state, LocationId locationId) {
            checkArgument(timingNodeId != null, "timingNodeId must not be null");
            checkArgument(state != null, "state must not be null");
            this.timingNodeId = timingNodeId;
            this.state = state;
            this.locationId = locationId;
        }

        @Override
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

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Status)) return false;
            Status that = (Status) other;
            if (!timingNodeId.equals(that.timingNodeId) || state != that.state) return false;
            return locationId == null ? that.locationId == null : locationId.equals(that.locationId);
        }

        @Override
        public int hashCode() {
            int result = timingNodeId.hashCode();
            result = 31 * result + state.hashCode();
            result = 31 * result + (locationId == null ? 0 : locationId.hashCode());
            return result;
        }
    }

    /**
     * Signals that node startup failed before normal command handling became
     * available.
     */
    public static final class StartupException extends RuntimeException {
        /** Creates a startup failure with its underlying cause. */
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

        /** Creates an operation failure without a lower-level cause. */
        public OperationException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        /** Creates an operation failure with the underlying execution cause. */
        public OperationException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        /** Returns the stable caller-visible failure category. */
        public Reason reason() {
            return reason;
        }
    }
}
