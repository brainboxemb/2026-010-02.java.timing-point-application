package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.util.function.BooleanSupplier;

/** Periodically transfers inventory between already prepared group members. */
final class AntennaSwitchTask implements CooperativeTask {

    private enum Phase {
        WAIT,
        STOP_CURRENT,
        START_NEXT
    }

    private final AntennaTasks.SwitchTarget switching;
    private final BooleanSupplier inventoryRequested;

    private Phase phase = Phase.WAIT;

    AntennaSwitchTask(
            AntennaTasks.SwitchTarget switching,
            BooleanSupplier inventoryRequested) {
        if (switching == null) {
            throw new IllegalArgumentException(
                    "switching must not be null");
        }
        if (inventoryRequested == null) {
            throw new IllegalArgumentException(
                    "inventoryRequested must not be null");
        }
        this.switching = switching;
        this.inventoryRequested = inventoryRequested;
    }

    @Override
    public TaskStep runStep() {
        if (!inventoryRequested.getAsBoolean()
                || !switching.rotationNeeded()) {
            return TaskStep.done();
        }

        switch (phase) {
            case WAIT:
                phase = Phase.STOP_CURRENT;
                return TaskStep.after(
                        switching.inventoryInterval());

            case STOP_CURRENT:
                if (!switching.stopCurrent()) {
                    return TaskStep.done();
                }
                phase = Phase.START_NEXT;
                return TaskStep.again();

            case START_NEXT:
                if (!switching.startNextAvailable()) {
                    return TaskStep.done();
                }
                phase = Phase.WAIT;
                return TaskStep.again();

            default:
                throw new IllegalStateException(
                        "Unsupported antenna-switch phase " + phase);
        }
    }
}
