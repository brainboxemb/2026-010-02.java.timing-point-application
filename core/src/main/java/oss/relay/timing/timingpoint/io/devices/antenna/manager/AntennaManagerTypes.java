package oss.relay.timing.timingpoint.io.devices.antenna.manager;

import oss.relay.timing.timingpoint.io.devices.antenna.AntennaId;

/**
 * Public lifecycle, self-test and failure values exposed by AntennaManager.
 */
public final class AntennaManagerTypes {
    /** Prevents instantiation of this source-code grouping class. */
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
        INVENTORY
    }

    /** Immutable point-in-time view of one configured antenna. */
    public static final class AntennaStatus {
        private final AntennaId antennaId;
        private final boolean selfTestPassed;
        private final AntennaOperation operation;
        private final Throwable failure;

        /** Creates one immutable point-in-time antenna status snapshot. */
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

        /** Returns the configured software identity of this antenna. */
        public AntennaId antennaId() {
            return antennaId;
        }

        /** Reports whether startup self-test completed successfully. */
        public boolean selfTestPassed() {
            return selfTestPassed;
        }

        /** Returns the current operational preparation/inventory state. */
        public AntennaOperation operation() {
            return operation;
        }

        /** Returns the current antenna failure, or null when none is recorded. */
        public Throwable failure() {
            return failure;
        }
    }
}
