package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.util.List;

/** Stops inventory and removes optional external power one antenna per turn. */
final class InventoryDisableTask implements CooperativeTask {

    private final List<? extends AntennaTasks.AntennaTarget> antennas;
    private int antennaIndex;
    private RuntimeException failure;

    InventoryDisableTask(
            List<? extends AntennaTasks.AntennaTarget> antennas) {
        if (antennas == null || antennas.isEmpty()) {
            throw new IllegalArgumentException(
                    "antennas must contain at least one antenna");
        }
        this.antennas = antennas;
        antennaIndex = antennas.size() - 1;
    }

    @Override
    public TaskStep runStep() {
        if (antennaIndex < 0) {
            return finish();
        }

        AntennaTasks.AntennaTarget antenna =
                antennas.get(antennaIndex--);

        if (!antenna.disableInventory()) {
            RuntimeException current =
                    antenna.failure() instanceof RuntimeException
                            ? (RuntimeException) antenna.failure()
                            : new IllegalStateException(
                                    "antenna inventory disable failed",
                                    antenna.failure());

            if (failure == null) {
                failure = current;
            } else if (failure != current) {
                failure.addSuppressed(current);
            }
        }

        return antennaIndex < 0
                ? finish()
                : TaskStep.again();
    }

    private TaskStep finish() {
        if (failure != null) {
            throw failure;
        }
        return TaskStep.done();
    }
}
