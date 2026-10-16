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
