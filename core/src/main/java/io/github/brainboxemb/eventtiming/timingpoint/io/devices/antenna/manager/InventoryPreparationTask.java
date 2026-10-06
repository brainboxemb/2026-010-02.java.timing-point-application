package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Cooperatively prepares one or more antennas for inventory.
 *
 * <p>This task stops at READY. It deliberately does not start inventory, so a
 * single-antenna owner or an {@link AntennaSwitchController} can decide who
 * starts inventory after every required antenna is initialized.</p>
 */
final class InventoryPreparationTask implements CooperativeTask {

    private enum Phase {
        BEGIN_PREPARE,
        COMPLETE_PREPARE
    }

    private final List<ManagedAntenna> antennas;
    private final BooleanSupplier stillRequested;

    private int antennaIndex;
    private Phase phase =
            Phase.BEGIN_PREPARE;

    InventoryPreparationTask(
            List<ManagedAntenna> antennas,
            BooleanSupplier stillRequested) {
        if (antennas == null
                || antennas.isEmpty()) {
            throw new IllegalArgumentException(
                    "antennas must contain at least one antenna");
        }
        if (stillRequested == null) {
            throw new IllegalArgumentException(
                    "stillRequested must not be null");
        }

        this.antennas = antennas;
        this.stillRequested = stillRequested;
    }

    @Override
    public TaskStep runStep() {
        if (!stillRequested.getAsBoolean()
                || antennaIndex >= antennas.size()) {
            return TaskStep.done();
        }

        ManagedAntenna antenna =
                antennas.get(
                        antennaIndex);

        switch (phase) {
            case BEGIN_PREPARE:
                Duration delay =
                        antenna.beginPrepareForInventory();
                if (delay == null) {
                    throw currentFailure(
                            antenna,
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
                            antenna,
                            "antenna initialization did not complete");
                }

                antennaIndex++;
                phase = Phase.BEGIN_PREPARE;
                return antennaIndex >= antennas.size()
                        ? TaskStep.done()
                        : TaskStep.again();

            default:
                throw new IllegalStateException(
                        "Unsupported inventory-preparation phase "
                                + phase);
        }
    }

    private static RuntimeException currentFailure(
            ManagedAntenna antenna,
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
