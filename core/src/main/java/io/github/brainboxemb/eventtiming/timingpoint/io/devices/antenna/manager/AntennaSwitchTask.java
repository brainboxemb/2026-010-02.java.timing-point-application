package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.util.function.BooleanSupplier;

/**
 * Periodically transfers inventory between already prepared group members.
 */
final class AntennaSwitchTask implements CooperativeTask {

    private final AntennaSwitchController switching;
    private final BooleanSupplier inventoryRequested;

    private boolean waitingForFirstInterval;

    AntennaSwitchTask(
            AntennaSwitchController switching,
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

        if (!waitingForFirstInterval) {
            waitingForFirstInterval = true;
            return TaskStep.after(
                    switching.inventoryInterval());
        }

        switching.rotateInventoryGroup();

        if (!inventoryRequested.getAsBoolean()
                || !switching.rotationNeeded()) {
            return TaskStep.done();
        }

        return TaskStep.after(
                switching.inventoryInterval());
    }
}
