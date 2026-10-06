package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;

/**
 * Public lifecycle, self-test and failure values exposed by AntennaManager.
 */
public final class AntennaManagerTypes {
    private AntennaManagerTypes() {
    }

    /** Lifecycle of the AntennaManager software component itself. */
    public enum State {
        NEW,
        ACTIVE,
        DEACTIVATING,
        INACTIVE,
        FAILED
    }

    /**
     * Current operational preparation of one configured antenna.
     *
     * <p>Startup self-test PASS/FAIL is exposed separately from this operational
     * state.</p>
     */
    public enum AntennaOperation {
        INACTIVE,
        PREPARING,
        READY,
        INVENTORY,
        SHUTDOWN
    }

    /** Failure category for a result-bearing manager control operation. */
    public enum FailureReason {
        OVERLOADED,
        TIMEOUT,
        INTERRUPTED,
        PROVIDER_FAILURE
    }

    /** Visible failure of one result-bearing manager control operation. */
    public static final class ControlException extends RuntimeException {
        private final FailureReason reason;

        ControlException(
                FailureReason reason,
                String message,
                Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public FailureReason reason() {
            return reason;
        }
    }

    /** Immutable point-in-time view of one configured antenna. */
    public static final class AntennaStatus {
        private final AntennaId antennaId;
        private final boolean selfTestPassed;
        private final AntennaOperation operation;
        private final Throwable failure;

        AntennaStatus(
                AntennaId antennaId,
                boolean selfTestPassed,
                AntennaOperation operation,
                Throwable failure) {
            this.antennaId = antennaId;
            this.selfTestPassed = selfTestPassed;
            this.operation = operation;
            this.failure = failure;
        }

        public AntennaId antennaId() {
            return antennaId;
        }

        public boolean selfTestPassed() {
            return selfTestPassed;
        }

        public AntennaOperation operation() {
            return operation;
        }

        public Throwable failure() {
            return failure;
        }
    }
}
