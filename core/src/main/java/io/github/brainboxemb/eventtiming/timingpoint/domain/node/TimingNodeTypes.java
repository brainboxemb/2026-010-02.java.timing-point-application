package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;

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

    /** Functional OPEN/CLOSED state of a TimingNode. */
    public enum FunctionalState {
        /** No timing location is currently open for participant registration. */
        CLOSED,

        /** A timing location is open for participant registration. */
        OPEN
    }

    /** Operational health of a TimingNode, independent from functional state. */
    public enum OperationalState {
        /** Startup/recovery readiness is still being established. */
        INIT,

        /** Required TimingNode capabilities are operating normally. */
        OK,

        /** One or more required TimingNode capabilities have failed. */
        ERROR
    }

    /** Error categories reported by {@link OperationalProblem}. */
    public enum ErrorCode {
        /** Existing persisted TimingData could not be loaded or validated at startup. */
        TIMING_DATA_LOAD_FAILED,

        /** A TimingData command failed while being committed to the LogBook. */
        TIMING_DATA_COMMIT_FAILED,

        /** LogBook work could not be queued for execution. */
        LOGBOOK_UNAVAILABLE
    }

    /** Immutable detail for the current TimingNode operational problem. */
    public static final class OperationalProblem {
        public final ErrorCode code;
        public final String message;

        public OperationalProblem(ErrorCode code, String message) {
            checkArgument(code != null, "code must not be null");
            checkArgument(message != null && !message.trim().isEmpty(), "message must not be blank");
            this.code = code;
            this.message = message.trim();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof OperationalProblem)) return false;
            OperationalProblem that = (OperationalProblem) other;
            return code == that.code
                    && message.equals(that.message);
        }

        @Override
        public int hashCode() {
            int result = code.hashCode();
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
            /** The operation cannot currently be accepted because execution capacity is busy. */
            BUSY,

            /** The node operation is not currently available. */
            UNAVAILABLE,

            /** The node operation failed during execution. */
            FAILED,

            /** The caller thread was interrupted while waiting for a result. */
            INTERRUPTED,

            /**
             * Waiting exceeded the caller wait guard. An accepted state-changing
             * command may still complete later, so its final outcome can be
             * unknown; a query timeout only means no query result was obtained.
             */
            TIMEOUT
        }

        private final Reason reason;

        /** Creates an operation failure without a lower-level cause. */
        public OperationException(Reason reason, String message) {
            super(operationMessage(message));
            checkArgument(reason != null, "reason must not be null");
            this.reason = reason;
        }

        /** Creates an operation failure with the underlying execution cause. */
        public OperationException(Reason reason, String message, Throwable cause) {
            super(operationMessage(message), cause);
            checkArgument(reason != null, "reason must not be null");
            this.reason = reason;
        }

        /** Validates and normalizes the caller-visible operation message. */
        private static String operationMessage(String message) {
            checkArgument(message != null && !message.trim().isEmpty(), "message must not be blank");
            return message.trim();
        }

        /** Returns the stable caller-visible failure category. */
        public Reason reason() {
            return reason;
        }
    }
}
