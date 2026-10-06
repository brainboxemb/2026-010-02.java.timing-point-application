package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

/**
 * Completion result emitted by a manager-owned antenna task.
 *
 * <p>The task keeps its execution handle internally; the manager only needs to
 * know whether the completed operation succeeded and, when it did not, why.</p>
 */
final class AntennaTaskResult {
    private static final AntennaTaskResult SUCCESS = new AntennaTaskResult(null);

    private final Throwable failure;

    private AntennaTaskResult(Throwable failure) {
        this.failure = failure;
    }

    static AntennaTaskResult success() {
        return SUCCESS;
    }

    static AntennaTaskResult failed(Throwable failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        return new AntennaTaskResult(failure);
    }

    boolean successful() {
        return failure == null;
    }

    Throwable failure() {
        return failure;
    }
}
