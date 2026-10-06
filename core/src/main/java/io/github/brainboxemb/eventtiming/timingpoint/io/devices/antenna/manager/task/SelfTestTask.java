package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * Reusable startup self-test for the complete configured antenna set.
 *
 * <p>The task owns its execution handle. Completion is published only after the
 * runner has finished the task, so callers never need to observe its Future.</p>
 */
final class SelfTestTask implements CooperativeTask {

    private enum Phase {
        POWER_ON,
        SELF_TEST,
        POWER_OFF
    }

    private final List<ManagedAntenna> antennas;
    private final Event<AntennaTaskResult> completedEvent = new Event<AntennaTaskResult>();

    private CompletableFuture<Void> operation;
    private int antennaIndex;
    private Phase phase;
    private RuntimeException failure;

    SelfTestTask(List<ManagedAntenna> antennas) {
        this.antennas = antennas;
        reset();
    }

    void start(ScheduledTaskRunner taskRunner) {
        checkState(!isRunning(), "SelfTestTask is already running");
        reset();
        operation = taskRunner.runTask(this);
        operation.whenComplete(this::onCompleted);
    }

    void cancel() {
        if (isRunning()) {
            operation.cancel(true);
        }
    }

    boolean isRunning() {
        return operation != null && !operation.isDone();
    }

    EventSource<AntennaTaskResult> completedEvent() {
        return completedEvent;
    }

    private void reset() {
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

    private void onCompleted(Void ignored, Throwable taskFailure) {
        if (taskFailure instanceof CancellationException) {
            return;
        }

        Throwable effectiveFailure = taskFailure != null ? taskFailure : failure;
        completedEvent.emit(
                effectiveFailure == null
                        ? AntennaTaskResult.success()
                        : AntennaTaskResult.failed(effectiveFailure));
    }

    private TaskStep finish() {
        return TaskStep.done();
    }
}
