package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;
import java.util.List;

/**
 * Reusable startup self-test for the complete configured antenna set.
 *
 * <p>The task owns the complete round: power, physical stabilization, provider
 * self-test and power-off. A failure on one antenna is remembered while the
 * remaining antennas are still tested.</p>
 */
final class SelfTestTask implements CooperativeTask {

    private enum Phase {
        POWER_ON,
        SELF_TEST,
        POWER_OFF
    }

    private final List<? extends AntennaTasks.AntennaTarget> antennas;

    private int antennaIndex;
    private Phase phase;
    private RuntimeException failure;

    SelfTestTask(List<? extends AntennaTasks.AntennaTarget> antennas) {
        this.antennas = antennas;
        reset();
    }

    void reset() {
        antennaIndex = 0;
        phase = Phase.POWER_ON;
        failure = null;
    }

    @Override
    public TaskStep runStep() {
        if (antennaIndex >= antennas.size()) {
            return finish();
        }

        AntennaTasks.AntennaTarget antenna = antennas.get(antennaIndex);

        switch (phase) {
            case POWER_ON:
                try {
                    antenna.powerOn();
                    phase = Phase.SELF_TEST;
                    Duration delay = antenna.powerStabilization();
                    return delay.isZero() ? TaskStep.again() : TaskStep.after(delay);
                } catch (RuntimeException ex) {
                    remember(ex);
                    phase = Phase.POWER_OFF;
                    return TaskStep.again();
                }

            case SELF_TEST:
                try {
                    antenna.selfTest();
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
                antennaIndex++;
                phase = Phase.POWER_ON;
                return antennaIndex >= antennas.size() ? finish() : TaskStep.again();

            default:
                throw new IllegalStateException("Unsupported self-test phase " + phase);
        }
    }

    private void remember(RuntimeException current) {
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
