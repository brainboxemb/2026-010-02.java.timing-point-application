package oss.relay.timing.timingpoint.io.devices.antenna.manager;

import oss.relay.timing.timingpoint.platform.events.Event;
import oss.relay.timing.timingpoint.platform.events.EventSource;
import oss.relay.timing.timingpoint.platform.execution.tasks.AbstractTask;
import oss.relay.timing.timingpoint.platform.execution.tasks.TaskStep;

import java.time.Duration;
import java.util.List;

/**
 * Reusable startup self-test for the complete configured antenna set.
 *
 * <p>The task owns its execution handle. Completion is published only after the
 * runner has finished the task, so callers never need to observe its Future.</p>
 */
final class SelfTestTask extends AbstractTask {

    private enum Phase {
        POWER_ON,
        SELF_TEST,
        POWER_OFF
    }

    private final List<ManagedAntenna> antennas;
    private final Event<AntennaTaskResult> completed = new Event<AntennaTaskResult>();

    /** Completion source consumed by AntennaManager during fixed composition. */
    final EventSource<AntennaTaskResult> COMPLETED = completed;

    private volatile AntennaTaskResult lastResult;
    private int antennaIndex;
    private Phase phase;
    private RuntimeException failure;

    SelfTestTask(List<ManagedAntenna> antennas) {
        this.antennas = antennas;
        resetForRun();
    }

    /**
     * Result of the most recently completed run, or null while no run has
     * completed since the latest start.
     */
    AntennaTaskResult lastResult() {
        return lastResult;
    }

    @Override
    protected void resetForRun() {
        lastResult = null;
        antennaIndex = 0;
        phase = Phase.POWER_ON;
        failure = null;
    }

    @Override
    public TaskStep runStep() {
        if (antennaIndex >= antennas.size()) {
            return finish();
        }

        ManagedAntenna antenna = antennas.get(antennaIndex);

        switch (phase) {
            case POWER_ON:
                try {
                    antenna.beginSelfTest();
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

    @Override
    protected void onRunCompleted(Throwable taskFailure) {
        Throwable effectiveFailure = taskFailure != null ? taskFailure : failure;
        lastResult = effectiveFailure == null
                ? AntennaTaskResult.success()
                : AntennaTaskResult.failed(effectiveFailure);

        completed.emit(lastResult);
    }

    private TaskStep finish() {
        return TaskStep.done();
    }
}
