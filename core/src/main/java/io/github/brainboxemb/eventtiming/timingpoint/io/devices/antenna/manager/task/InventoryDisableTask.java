package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.util.List;

/** Reusable inventory-disable state machine. */
final class InventoryDisableTask implements CooperativeTask {

    private enum Phase {
        STOP_INVENTORY,
        POWER_OFF
    }

    private final List<? extends AntennaTasks.AntennaTarget> antennas;

    private int antennaIndex;
    private Phase phase;
    private RuntimeException failure;

    InventoryDisableTask(
            List<? extends AntennaTasks.AntennaTarget> antennas) {
        this.antennas = antennas;
        reset();
    }

    void reset() {
        antennaIndex = antennas.size() - 1;
        phase = Phase.STOP_INVENTORY;
        failure = null;
    }

    @Override
    public TaskStep runStep() {
        if (antennaIndex < 0) {
            return finish();
        }

        AntennaTasks.AntennaTarget antenna =
                antennas.get(antennaIndex);

        switch (phase) {
            case STOP_INVENTORY:
                try {
                    antenna.stopInventory();
                } catch (RuntimeException ex) {
                    remember(ex);
                }
                phase = Phase.POWER_OFF;
                return TaskStep.again();

            case POWER_OFF:
                try {
                    antenna.powerOffAfterInventory();
                } catch (RuntimeException ex) {
                    remember(ex);
                }
                antennaIndex--;
                phase = Phase.STOP_INVENTORY;
                return antennaIndex < 0
                        ? finish()
                        : TaskStep.again();

            default:
                throw new IllegalStateException(
                        "Unsupported inventory-disable phase " + phase);
        }
    }

    private void remember(
            RuntimeException current) {
        if (failure == null) {
            failure = current;
        } else if (failure != current) {
            failure.addSuppressed(current);
        }
    }

    private TaskStep finish() {
        if (failure != null) {
            throw failure;
        }
        return TaskStep.done();
    }
}
