package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;

/** Reusable self-test state machine for one managed antenna. */
final class SelfTestTask implements AntennaTasks.ReusableTask {

    private enum Phase {
        POWER_ON,
        SELF_TEST,
        POWER_OFF,
        FINISH
    }

    private final AntennaTasks.AntennaTarget antenna;

    private Phase phase;
    private RuntimeException failure;

    SelfTestTask(
            AntennaTasks.AntennaTarget antenna) {
        this.antenna = antenna;
        reset();
    }

    @Override
    public void reset() {
        phase = Phase.POWER_ON;
        failure = null;
    }

    @Override
    public TaskStep runStep() {
        switch (phase) {
            case POWER_ON:
                try {
                    antenna.powerOn();
                    phase = Phase.SELF_TEST;

                    Duration delay =
                            antenna.powerStabilization();
                    return delay.isZero()
                            ? TaskStep.again()
                            : TaskStep.after(
                                    delay);
                } catch (RuntimeException ex) {
                    remember(
                            ex);
                    phase = Phase.POWER_OFF;
                    return TaskStep.again();
                }

            case SELF_TEST:
                try {
                    antenna.selfTest();
                } catch (RuntimeException ex) {
                    remember(
                            ex);
                }
                phase = Phase.POWER_OFF;
                return TaskStep.again();

            case POWER_OFF:
                try {
                    antenna.powerOff();
                } catch (RuntimeException ex) {
                    remember(
                            ex);
                }
                phase = Phase.FINISH;
                return TaskStep.again();

            case FINISH:
                if (failure != null) {
                    throw failure;
                }
                return TaskStep.done();

            default:
                throw new IllegalStateException(
                        "Unsupported self-test phase "
                                + phase);
        }
    }

    private void remember(
            RuntimeException current) {
        if (failure == null) {
            failure = current;
        } else if (failure != current) {
            failure.addSuppressed(
                    current);
        }
    }
}
