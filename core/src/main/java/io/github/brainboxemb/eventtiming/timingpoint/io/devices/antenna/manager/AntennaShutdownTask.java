package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.util.List;

/** Shuts down one configured antenna per cooperative turn. */
final class AntennaShutdownTask implements CooperativeTask {

    private final List<ManagedAntenna> antennas;
    private int antennaIndex;
    private RuntimeException failure;

    AntennaShutdownTask(
            List<ManagedAntenna> antennas) {
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
            if (failure != null) {
                throw failure;
            }
            return TaskStep.done();
        }

        try {
            antennas.get(antennaIndex--).shutdown();
        } catch (RuntimeException ex) {
            if (failure == null) {
                failure = ex;
            } else if (failure != ex) {
                failure.addSuppressed(ex);
            }
        }

        if (antennaIndex >= 0) {
            return TaskStep.again();
        }
        if (failure != null) {
            throw failure;
        }
        return TaskStep.done();
    }
}
