package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Reusable inventory-enable state machine. */
final class InventoryEnableTask implements CooperativeTask {

    private enum Phase {
        POWER_ON,
        INITIALIZE,
        START_DIRECT,
        START_GROUP
    }

    private final List<? extends AntennaTasks.AntennaTarget> antennas;
    private final List<? extends AntennaTasks.AntennaTarget> inventoryGroup;
    private final BooleanSupplier stillRequested;

    private int antennaIndex;
    private Phase phase;

    InventoryEnableTask(
            List<? extends AntennaTasks.AntennaTarget> antennas,
            List<? extends AntennaTasks.AntennaTarget> inventoryGroup,
            BooleanSupplier stillRequested) {
        this.antennas = antennas;
        this.inventoryGroup = inventoryGroup;
        this.stillRequested = stillRequested;
        reset();
    }

    void reset() {
        antennaIndex = 0;
        phase = Phase.POWER_ON;
    }

    @Override
    public TaskStep runStep() {
        if (!stillRequested.getAsBoolean()) {
            return TaskStep.done();
        }

        if (phase == Phase.START_GROUP) {
            startFirstAvailableGroupMember();
            return TaskStep.done();
        }

        if (antennaIndex >= antennas.size()) {
            if (inventoryGroup.isEmpty()) {
                return TaskStep.done();
            }

            phase = Phase.START_GROUP;
            return TaskStep.again();
        }

        AntennaTasks.AntennaTarget antenna =
                antennas.get(antennaIndex);

        switch (phase) {
            case POWER_ON:
                Duration delay =
                        antenna.powerOnForInventory();
                phase = Phase.INITIALIZE;
                return delay.isZero()
                        ? TaskStep.again()
                        : TaskStep.after(delay);

            case INITIALIZE:
                antenna.initialize();
                if (antenna.inInventoryGroup()) {
                    moveToNextAntenna();
                } else {
                    phase = Phase.START_DIRECT;
                }
                return TaskStep.again();

            case START_DIRECT:
                antenna.startInventory();
                moveToNextAntenna();
                return TaskStep.again();

            default:
                throw new IllegalStateException(
                        "Unsupported inventory-enable phase " + phase);
        }
    }

    private void startFirstAvailableGroupMember() {
        RuntimeException lastFailure = null;

        for (AntennaTasks.AntennaTarget antenna : inventoryGroup) {
            if (!antenna.availableForInventory()) {
                continue;
            }

            try {
                antenna.startInventory();
                return;
            } catch (RuntimeException ex) {
                lastFailure = ex;
            }
        }

        if (lastFailure != null) {
            throw lastFailure;
        }

        throw new IllegalStateException(
                "no prepared antenna could start inventory");
    }

    private void moveToNextAntenna() {
        antennaIndex++;
        phase = Phase.POWER_ON;
    }
}
