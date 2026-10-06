package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;
import java.util.List;

/**
 * Cooperative startup self-test for the configured antennas.
 *
 * <p>One invocation performs one logical step. Device stabilization never
 * occupies the shared I/O worker.</p>
 */
final class SelfTestTask implements CooperativeTask {

    private enum Phase {
        BEGIN,
        COMPLETE
    }

    private final List<ManagedAntenna> antennas;

    private int antennaIndex;
    private Phase phase =
            Phase.BEGIN;

    SelfTestTask(
            List<ManagedAntenna> antennas) {
        if (antennas == null
                || antennas.isEmpty()) {
            throw new IllegalArgumentException(
                    "antennas must contain at least one antenna");
        }
        this.antennas = antennas;
    }

    @Override
    public TaskStep runStep() {
        if (antennaIndex >= antennas.size()) {
            return TaskStep.done();
        }

        ManagedAntenna antenna =
                antennas.get(
                        antennaIndex);

        switch (phase) {
            case BEGIN:
                Duration delay =
                        antenna.beginSelfTest();

                if (delay == null) {
                    moveToNextAntenna();
                    return nextStep();
                }

                phase = Phase.COMPLETE;
                return delay.isZero()
                        ? TaskStep.again()
                        : TaskStep.after(
                                delay);

            case COMPLETE:
                antenna.completeSelfTest();
                moveToNextAntenna();
                return nextStep();

            default:
                throw new IllegalStateException(
                        "Unsupported self-test phase "
                                + phase);
        }
    }

    private void moveToNextAntenna() {
        antennaIndex++;
        phase = Phase.BEGIN;
    }

    private TaskStep nextStep() {
        return antennaIndex >= antennas.size()
                ? TaskStep.done()
                : TaskStep.again();
    }
}
