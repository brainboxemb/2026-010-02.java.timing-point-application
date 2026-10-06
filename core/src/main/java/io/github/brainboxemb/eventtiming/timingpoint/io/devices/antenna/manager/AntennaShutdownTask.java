package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.util.List;

/** Reusable antenna shutdown state machine. */
final class AntennaShutdownTask implements CooperativeTask {

    private enum Phase {
        STOP_INVENTORY,
        POWER_OFF,
        SHUTDOWN_PROVIDER
    }

    private final List<ManagedAntenna> antennas;

    private int antennaIndex;
    private Phase phase;
    private RuntimeException failure;

    AntennaShutdownTask(
            List<ManagedAntenna> antennas) {
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

        ManagedAntenna antenna =
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
                    antenna.powerOff();
                } catch (RuntimeException ex) {
                    remember(ex);
                }
                phase = Phase.SHUTDOWN_PROVIDER;
                return TaskStep.again();

            case SHUTDOWN_PROVIDER:
                try {
                    antenna.shutdownProvider();
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
                        "Unsupported shutdown phase " + phase);
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
