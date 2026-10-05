package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

/** Public lifecycle, status and failure values exposed by AntennaManager. */
public final class AntennaManagerTypes {
    private AntennaManagerTypes() {
    }

    public enum State {
        NEW,
        STARTING,
        RUNNING,
        DEGRADED,
        STOPPING,
        STOPPED,
        FAILED
    }

    public enum AntennaState {
        UNCHECKED,
        CHECKING,
        READY,
        INVENTORY,
        ERROR,
        CLOSED
    }

    public enum FailureReason {
        OVERLOADED,
        TIMEOUT,
        INTERRUPTED,
        PROVIDER_FAILURE
    }

    /** Visible failure of one result-bearing antenna lifecycle operation. */
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
        private final AntennaState state;
        private final Throwable failure;

        AntennaStatus(
                AntennaId antennaId,
                AntennaState state,
                Throwable failure) {
            this.antennaId = antennaId;
            this.state = state;
            this.failure = failure;
        }

        public AntennaId antennaId() {
            return antennaId;
        }

        public AntennaState state() {
            return state;
        }

        public Throwable failure() {
            return failure;
        }

        public boolean healthy() {
            return state == AntennaState.READY
                    || state == AntennaState.INVENTORY;
        }
    }
}
