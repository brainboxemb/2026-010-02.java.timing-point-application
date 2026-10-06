package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Cooperative inventory-enable transition for one antenna.
 *
 * <p>Power preparation, initialization and inventory start are separate turns
 * on the shared serial I/O lane. A newer disable request can stop the task
 * between turns.</p>
 */
final class InventoryEnableTask implements CooperativeTask {

    private enum Phase {
        BEGIN_PREPARE,
        COMPLETE_PREPARE,
        START_INVENTORY
    }

    private final ManagedAntenna antenna;
    private final BooleanSupplier stillRequested;

    private Phase phase =
            Phase.BEGIN_PREPARE;

    InventoryEnableTask(
            ManagedAntenna antenna,
            BooleanSupplier stillRequested) {
        if (antenna == null) {
            throw new IllegalArgumentException(
                    "antenna must not be null");
        }
        if (stillRequested == null) {
            throw new IllegalArgumentException(
                    "stillRequested must not be null");
        }
        this.antenna = antenna;
        this.stillRequested = stillRequested;
    }

    @Override
    public TaskStep runStep() {
        if (!stillRequested.getAsBoolean()) {
            return TaskStep.done();
        }

        switch (phase) {
            case BEGIN_PREPARE:
                Duration delay =
                        antenna.beginPrepareForInventory();
                if (delay == null) {
                    throw currentFailure(
                            "antenna could not begin inventory preparation");
                }

                phase = Phase.COMPLETE_PREPARE;
                return delay.isZero()
                        ? TaskStep.again()
                        : TaskStep.after(
                                delay);

            case COMPLETE_PREPARE:
                if (!antenna.completePrepareForInventory()) {
                    throw currentFailure(
                            "antenna initialization did not complete");
                }
                phase = Phase.START_INVENTORY;
                return TaskStep.again();

            case START_INVENTORY:
                if (!antenna.startInventory()) {
                    throw currentFailure(
                            "antenna inventory did not start");
                }
                return TaskStep.done();

            default:
                throw new IllegalStateException(
                        "Unsupported inventory-enable phase "
                                + phase);
        }
    }

    private RuntimeException currentFailure(
            String message) {
        Throwable failure =
                antenna.failure();

        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        if (failure == null) {
            return new IllegalStateException(
                    message);
        }
        return new IllegalStateException(
                message,
                failure);
    }
}
