package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;
import java.util.List;

/** Cooperative startup self-test over the configured antenna set. */
final class SelfTestTask implements CooperativeTask {

    private enum Phase {
        POWER_ON,
        SELF_TEST,
        POWER_OFF
    }

    private final List<? extends AntennaTasks.AntennaTarget> antennas;
    private int antennaIndex;
    private Phase phase = Phase.POWER_ON;

    SelfTestTask(
            List<? extends AntennaTasks.AntennaTarget> antennas) {
        if (antennas == null || antennas.isEmpty()) {
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

        AntennaTasks.AntennaTarget antenna =
                antennas.get(antennaIndex);

        switch (phase) {
            case POWER_ON:
                try {
                    Duration delay =
                            antenna.powerOnForSelfTest();
                    phase = Phase.SELF_TEST;
                    return delay.isZero()
                            ? TaskStep.again()
                            : TaskStep.after(delay);
                } catch (RuntimeException ex) {
                    phase = Phase.POWER_OFF;
                    return TaskStep.again();
                }

            case SELF_TEST:
                try {
                    antenna.selfTest();
                } catch (RuntimeException ex) {
                    // Failure is stored on the managed antenna; continue cleanup.
                }
                phase = Phase.POWER_OFF;
                return TaskStep.again();

            case POWER_OFF:
                try {
                    antenna.powerOffAfterSelfTest();
                } catch (RuntimeException ex) {
                    // Failure is stored on the managed antenna; continue with next.
                }
                antennaIndex++;
                phase = Phase.POWER_ON;
                return antennaIndex >= antennas.size()
                        ? TaskStep.done()
                        : TaskStep.again();

            default:
                throw new IllegalStateException(
                        "Unsupported self-test phase " + phase);
        }
    }
}
