package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Reusable round-robin inventory switch state machine. */
final class AntennaSwitchTask implements CooperativeTask {

    private enum Phase {
        WAIT,
        STOP_CURRENT,
        START_NEXT
    }

    private final List<? extends AntennaTasks.AntennaTarget> inventoryGroup;
    private final Duration inventoryInterval;
    private final BooleanSupplier inventoryRequested;

    private Phase phase;
    private int nextIndex;

    AntennaSwitchTask(
            List<? extends AntennaTasks.AntennaTarget> inventoryGroup,
            Duration inventoryInterval,
            BooleanSupplier inventoryRequested) {
        this.inventoryGroup = inventoryGroup;
        this.inventoryInterval = inventoryInterval;
        this.inventoryRequested = inventoryRequested;
        reset();
    }

    void reset() {
        phase = Phase.WAIT;
        nextIndex = 0;
    }

    @Override
    public TaskStep runStep() {
        if (!inventoryRequested.getAsBoolean()
                || availableCount() < 2) {
            return TaskStep.done();
        }

        switch (phase) {
            case WAIT:
                phase = Phase.STOP_CURRENT;
                return TaskStep.after(
                        inventoryInterval);

            case STOP_CURRENT:
                if (!stopCurrent()) {
                    return TaskStep.done();
                }
                phase = Phase.START_NEXT;
                return TaskStep.again();

            case START_NEXT:
                if (!startNextAvailable()) {
                    return TaskStep.done();
                }
                phase = Phase.WAIT;
                return TaskStep.again();

            default:
                throw new IllegalStateException(
                        "Unsupported antenna-switch phase " + phase);
        }
    }

    private boolean stopCurrent() {
        int currentIndex =
                currentInventoryIndex();

        if (currentIndex < 0) {
            return true;
        }

        nextIndex =
                (currentIndex + 1)
                        % inventoryGroup.size();

        try {
            inventoryGroup.get(currentIndex)
                    .stopInventory();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private boolean startNextAvailable() {
        for (int offset = 0;
                offset < inventoryGroup.size();
                offset++) {
            int candidateIndex =
                    (nextIndex + offset)
                            % inventoryGroup.size();

            AntennaTasks.AntennaTarget candidate =
                    inventoryGroup.get(
                            candidateIndex);

            if (!candidate.availableForInventory()) {
                continue;
            }

            try {
                candidate.startInventory();
                nextIndex =
                        (candidateIndex + 1)
                                % inventoryGroup.size();
                return true;
            } catch (RuntimeException ex) {
                // Failure is stored on the antenna; try another prepared member.
            }
        }

        return false;
    }

    private int currentInventoryIndex() {
        for (int index = 0;
                index < inventoryGroup.size();
                index++) {
            if (inventoryGroup.get(index)
                    .inventoryRunning()) {
                return index;
            }
        }
        return -1;
    }

    private int availableCount() {
        int count = 0;
        for (AntennaTasks.AntennaTarget antenna : inventoryGroup) {
            if (antenna.availableForInventory()) {
                count++;
            }
        }
        return count;
    }
}
