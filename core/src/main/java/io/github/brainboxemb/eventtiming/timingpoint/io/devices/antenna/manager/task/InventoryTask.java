package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
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
 * Reusable inventory state machine for enable, disable and multiplex switching.
 *
 * <p>The task reads the current requested value on every turn. A changed request
 * therefore changes the next state without requiring a second controller or a
 * second inventory operation object.</p>
 */
final class InventoryTask implements CooperativeTask {

    private enum Phase {
        DECIDE,
        ENABLE_POWER_ON,
        ENABLE_INITIALIZE,
        ENABLE_START_DIRECT,
        ENABLE_START_GROUP,
        ENABLE_APPLY,
        SWITCH_WAIT,
        SWITCH_STOP,
        SWITCH_START,
        DISABLE_STOP,
        DISABLE_POWER_OFF,
        DISABLE_APPLY
    }

    private final List<? extends AntennaTasks.AntennaTarget> antennas;
    private final List<? extends AntennaTasks.AntennaTarget> inventoryGroup;
    private final Duration inventoryInterval;
    private final Setting<Boolean> inventoryEnabledSetting;
    private final Event<AntennaTasks.TaskResult> completedEvent = new Event<AntennaTasks.TaskResult>();

    private CompletableFuture<Void> operation;
    private Phase phase;
    private int antennaIndex;
    private int groupIndex;
    private int groupAttempts;
    private RuntimeException groupFailure;
    private RuntimeException disableFailure;

    InventoryTask(
            List<? extends AntennaTasks.AntennaTarget> antennas,
            List<? extends AntennaTasks.AntennaTarget> inventoryGroup,
            Duration inventoryInterval,
            Setting<Boolean> inventoryEnabledSetting) {
        this.antennas = antennas;
        this.inventoryGroup = inventoryGroup;
        this.inventoryInterval = inventoryInterval;
        this.inventoryEnabledSetting = inventoryEnabledSetting;
        reset();
    }

    void start(ScheduledTaskRunner taskRunner) {
        checkState(!isRunning(), "InventoryTask is already running");
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

    EventSource<AntennaTasks.TaskResult> completedEvent() {
        return completedEvent;
    }

    private void onCompleted(Void ignored, Throwable taskFailure) {
        if (taskFailure instanceof CancellationException) {
            return;
        }

        completedEvent.emit(
                taskFailure == null
                        ? AntennaTasks.TaskResult.success()
                        : AntennaTasks.TaskResult.failed(taskFailure));
    }

    void reset() {
        phase = Phase.DECIDE;
        antennaIndex = 0;
        groupIndex = 0;
        groupAttempts = 0;
        groupFailure = null;
        disableFailure = null;
    }

    @Override
    public TaskStep runStep() {
        if (!inventoryRequestedEnabled()
                && isEnableOrSwitchPhase()) {
            beginDisable();
            return TaskStep.again();
        }

        switch (phase) {
            case DECIDE:
                return decide();

            case ENABLE_POWER_ON:
                return enablePowerOn();

            case ENABLE_INITIALIZE:
                return enableInitialize();

            case ENABLE_START_DIRECT:
                return enableStartDirect();

            case ENABLE_START_GROUP:
                return enableStartGroup();

            case ENABLE_APPLY:
                inventoryEnabledSetting.markApplied(
                        Boolean.TRUE);
                if (inventoryRequestedEnabled()
                        && availableGroupCount() > 1) {
                    phase = Phase.SWITCH_WAIT;
                    return TaskStep.after(
                            inventoryInterval);
                }
                return finishOrContinue();

            case SWITCH_WAIT:
                if (availableGroupCount() < 2) {
                    return TaskStep.done();
                }
                phase = Phase.SWITCH_STOP;
                return TaskStep.again();

            case SWITCH_STOP:
                return switchStop();

            case SWITCH_START:
                return switchStart();

            case DISABLE_STOP:
                return disableStop();

            case DISABLE_POWER_OFF:
                return disablePowerOff();

            case DISABLE_APPLY:
                if (disableFailure != null) {
                    throw disableFailure;
                }
                inventoryEnabledSetting.markApplied(
                        Boolean.FALSE);
                return finishOrContinue();

            default:
                throw new IllegalStateException(
                        "Unsupported inventory task phase " + phase);
        }
    }

    private TaskStep decide() {
        if (!inventoryEnabledSetting.changePending()) {
            return TaskStep.done();
        }

        if (inventoryRequestedEnabled()) {
            antennaIndex = 0;
            phase = Phase.ENABLE_POWER_ON;
        } else {
            beginDisable();
        }
        return TaskStep.again();
    }

    private TaskStep enablePowerOn() {
        if (antennaIndex >= antennas.size()) {
            if (inventoryGroup.isEmpty()) {
                phase = Phase.ENABLE_APPLY;
            } else {
                groupIndex = 0;
                groupAttempts = 0;
                groupFailure = null;
                phase = Phase.ENABLE_START_GROUP;
            }
            return TaskStep.again();
        }

        AntennaTasks.AntennaTarget antenna =
                antennas.get(antennaIndex);
        antenna.beginInventoryPreparation();
        antenna.powerOn();

        Duration delay =
                antenna.powerStabilization();
        phase = Phase.ENABLE_INITIALIZE;
        return delay.isZero()
                ? TaskStep.again()
                : TaskStep.after(
                        delay);
    }

    private TaskStep enableInitialize() {
        AntennaTasks.AntennaTarget antenna =
                antennas.get(antennaIndex);
        antenna.initialize();

        if (inventoryGroup.contains(
                antenna)) {
            moveToNextAntenna();
        } else {
            phase = Phase.ENABLE_START_DIRECT;
        }
        return TaskStep.again();
    }

    private TaskStep enableStartDirect() {
        antennas.get(antennaIndex)
                .startInventory();
        moveToNextAntenna();
        return TaskStep.again();
    }

    private TaskStep enableStartGroup() {
        if (groupAttempts >= inventoryGroup.size()) {
            if (groupFailure != null) {
                throw groupFailure;
            }
            throw new IllegalStateException(
                    "no prepared antenna could start inventory");
        }

        AntennaTasks.AntennaTarget candidate =
                inventoryGroup.get(groupIndex);
        groupIndex =
                (groupIndex + 1)
                        % inventoryGroup.size();
        groupAttempts++;

        if (!candidate.availableForInventory()) {
            return TaskStep.again();
        }

        try {
            candidate.startInventory();
            phase = Phase.ENABLE_APPLY;
        } catch (RuntimeException ex) {
            groupFailure = ex;
        }
        return TaskStep.again();
    }

    private TaskStep switchStop() {
        int currentIndex =
                currentGroupInventoryIndex();

        if (currentIndex < 0) {
            prepareSwitchStart(
                    groupIndex);
            return TaskStep.again();
        }

        AntennaTasks.AntennaTarget current =
                inventoryGroup.get(
                        currentIndex);
        current.stopInventory();

        prepareSwitchStart(
                (currentIndex + 1)
                        % inventoryGroup.size());
        return TaskStep.again();
    }

    private TaskStep switchStart() {
        if (groupAttempts >= inventoryGroup.size()) {
            if (groupFailure != null) {
                throw groupFailure;
            }
            return TaskStep.done();
        }

        AntennaTasks.AntennaTarget candidate =
                inventoryGroup.get(groupIndex);
        groupIndex =
                (groupIndex + 1)
                        % inventoryGroup.size();
        groupAttempts++;

        if (!candidate.availableForInventory()) {
            return TaskStep.again();
        }

        try {
            candidate.startInventory();
            phase = Phase.SWITCH_WAIT;
            return TaskStep.after(
                    inventoryInterval);
        } catch (RuntimeException ex) {
            groupFailure = ex;
            return TaskStep.again();
        }
    }

    private TaskStep disableStop() {
        if (antennaIndex < 0) {
            phase = Phase.DISABLE_APPLY;
            return TaskStep.again();
        }

        try {
            antennas.get(antennaIndex)
                    .stopInventory();
        } catch (RuntimeException ex) {
            rememberDisableFailure(
                    ex);
        }
        phase = Phase.DISABLE_POWER_OFF;
        return TaskStep.again();
    }

    private TaskStep disablePowerOff() {
        try {
            antennas.get(antennaIndex)
                    .powerOff();
        } catch (RuntimeException ex) {
            rememberDisableFailure(
                    ex);
        }

        antennaIndex--;
        phase = Phase.DISABLE_STOP;
        return TaskStep.again();
    }

    private void beginDisable() {
        antennaIndex =
                antennas.size() - 1;
        disableFailure = null;
        phase = Phase.DISABLE_STOP;
    }

    private void moveToNextAntenna() {
        antennaIndex++;
        phase = Phase.ENABLE_POWER_ON;
    }

    private void prepareSwitchStart(
            int startIndex) {
        groupIndex = startIndex;
        groupAttempts = 0;
        groupFailure = null;
        phase = Phase.SWITCH_START;
    }

    private TaskStep finishOrContinue() {
        if (inventoryEnabledSetting.changePending()) {
            phase = Phase.DECIDE;
            return TaskStep.again();
        }
        return TaskStep.done();
    }

    private boolean inventoryRequestedEnabled() {
        return Boolean.TRUE.equals(
                inventoryEnabledSetting.requestedValue());
    }

    private boolean isEnableOrSwitchPhase() {
        switch (phase) {
            case ENABLE_POWER_ON:
            case ENABLE_INITIALIZE:
            case ENABLE_START_DIRECT:
            case ENABLE_START_GROUP:
            case ENABLE_APPLY:
            case SWITCH_WAIT:
            case SWITCH_STOP:
            case SWITCH_START:
                return true;
            default:
                return false;
        }
    }

    private int currentGroupInventoryIndex() {
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

    private int availableGroupCount() {
        int count = 0;
        for (AntennaTasks.AntennaTarget antenna : inventoryGroup) {
            if (antenna.availableForInventory()) {
                count++;
            }
        }
        return count;
    }

    private void rememberDisableFailure(
            RuntimeException current) {
        if (disableFailure == null) {
            disableFailure = current;
        } else if (disableFailure != current) {
            disableFailure.addSuppressed(
                    current);
        }
    }
}
