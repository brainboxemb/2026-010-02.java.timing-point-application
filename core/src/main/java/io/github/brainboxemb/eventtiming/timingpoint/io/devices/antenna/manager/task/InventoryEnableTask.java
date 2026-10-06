package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Prepares every configured antenna and starts inventory according to whether
 * the antenna is independent or belongs to the mutual-exclusion switch group.
 */
final class InventoryEnableTask implements CooperativeTask {

    private enum Phase {
        BEGIN_PREPARE,
        COMPLETE_PREPARE,
        START_DIRECT,
        START_SWITCH_GROUP
    }

    private final List<? extends AntennaTasks.AntennaTarget> antennas;
    private final AntennaTasks.SwitchTarget switching;
    private final BooleanSupplier stillRequested;

    private int antennaIndex;
    private Phase phase = Phase.BEGIN_PREPARE;

    InventoryEnableTask(
            List<? extends AntennaTasks.AntennaTarget> antennas,
            AntennaTasks.SwitchTarget switching,
            BooleanSupplier stillRequested) {
        if (antennas == null || antennas.isEmpty()) {
            throw new IllegalArgumentException(
                    "antennas must contain at least one antenna");
        }
        if (switching == null) {
            throw new IllegalArgumentException(
                    "switching must not be null");
        }
        if (stillRequested == null) {
            throw new IllegalArgumentException(
                    "stillRequested must not be null");
        }

        this.antennas = antennas;
        this.switching = switching;
        this.stillRequested = stillRequested;
    }

    @Override
    public TaskStep runStep() {
        if (!stillRequested.getAsBoolean()) {
            return TaskStep.done();
        }

        if (phase == Phase.START_SWITCH_GROUP) {
            if (!switching.startFirstAvailable()) {
                throw new IllegalStateException(
                        "no prepared antenna could start inventory");
            }
            return TaskStep.done();
        }

        if (antennaIndex >= antennas.size()) {
            if (switching.hasInventoryGroup()) {
                phase = Phase.START_SWITCH_GROUP;
                return TaskStep.again();
            }
            return TaskStep.done();
        }

        AntennaTasks.AntennaTarget antenna =
                antennas.get(antennaIndex);

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
                        : TaskStep.after(delay);

            case COMPLETE_PREPARE:
                if (!antenna.completePrepareForInventory()) {
                    throw currentFailure(
                            antenna,
                            "antenna initialization did not complete");
                }

                if (antenna.inInventoryGroup()) {
                    moveToNextAntenna();
                } else {
                    phase = Phase.START_DIRECT;
                }
                return TaskStep.again();

            case START_DIRECT:
                if (!antenna.startInventory()) {
                    throw currentFailure(
                            antenna,
                            "antenna inventory did not start");
                }
                moveToNextAntenna();
                return TaskStep.again();

            default:
                throw new IllegalStateException(
                        "Unsupported inventory-enable phase " + phase);
        }
    }

    private void moveToNextAntenna() {
        antennaIndex++;
        phase = Phase.BEGIN_PREPARE;
    }

    private static RuntimeException currentFailure(
            AntennaTasks.AntennaTarget antenna,
            String message) {
        Throwable failure =
                antenna.failure();
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        return failure == null
                ? new IllegalStateException(message)
                : new IllegalStateException(message, failure);
    }
}
