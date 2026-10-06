package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;

/**
 * Public lifecycle, health and failure values exposed by AntennaManager.
 */
public final class AntennaManagerTypes {
    private AntennaManagerTypes() {
    }

    /**
     * Lifecycle of the AntennaManager software component itself.
     *
     * <p>Antenna health does not change this lifecycle state. A manager can stay
     * ACTIVE while one configured antenna is unavailable.</p>
     */
    public enum State {
        NEW,
        ACTIVE,
        DEACTIVATING,
        INACTIVE,
        FAILED
    }

    /** Aggregate health of the configured antenna set. */
    public enum ManagerHealth {
        UNKNOWN,
        HEALTHY,
        DEGRADED,
        FAILED
    }

    /** Health/availability of one configured antenna. */
    public enum AntennaHealth {
        UNKNOWN,
        CHECKING,
        HEALTHY,
        FAILED
    }

    /**
     * Current operational preparation of one configured antenna.
     *
     * <p>This is deliberately separate from health. A successfully probed
     * antenna can be HEALTHY while operationally INACTIVE and powered down.</p>
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
        private final AntennaHealth health;
        private final AntennaOperation operation;
        private final Throwable failure;

        AntennaStatus(
                AntennaId antennaId,
                AntennaHealth health,
                AntennaOperation operation,
                Throwable failure) {
            this.antennaId = antennaId;
            this.health = health;
            this.operation = operation;
            this.failure = failure;
        }

        public AntennaId antennaId() {
            return antennaId;
        }

        public AntennaHealth health() {
            return health;
        }

        public AntennaOperation operation() {
            return operation;
        }

        public Throwable failure() {
            return failure;
        }

        public boolean healthy() {
            return health == AntennaHealth.HEALTHY;
        }
    }
}
