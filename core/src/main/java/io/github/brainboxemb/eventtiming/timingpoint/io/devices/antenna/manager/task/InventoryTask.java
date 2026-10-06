package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;
import java.util.List;

/**
 * Reusable inventory state machine for enable, disable and multiplex switching.
 *
 * <p>The task always works toward the latest value in {@code inventoryEnabledSetting}:</p>
 *
 * <pre>
 * requested ON
 *   prepare each antenna -> start direct antennas -> start one group antenna
 *   -> wait -> stop current group antenna -> start next -> wait ...
 *
 * requested OFF
 *   stop antennas in reverse order -> power off -> applied=false
 * </pre>
 *
 * <p>A changed request does not create another task. The running task reads the
 * latest requested value on every turn and changes direction when needed.</p>
 */
final class InventoryTask extends AbstractTask {

    private enum Phase {
        DECIDE,
        POWER_ON,
        INITIALIZE,
        START_DIRECT,
        START_GROUP,
        SWITCH_STOP,
        SWITCH_START,
        DISABLE_STOP,
        DISABLE_POWER_OFF
    }

    private final List<ManagedAntenna> antennas;
    private final List<ManagedAntenna> inventoryGroup;
    private final Duration inventoryInterval;
    private final Setting<Boolean> inventoryEnabledSetting;
    private final Event<AntennaTaskResult> completedEvent = new Event<AntennaTaskResult>();

    private Phase phase;
    private int antennaIndex;
    private int groupIndex;
    private int groupAttempts;
    private RuntimeException groupFailure;
    private RuntimeException disableFailure;

    InventoryTask(
            List<ManagedAntenna> antennas,
            List<ManagedAntenna> inventoryGroup,
            Duration inventoryInterval,
            Setting<Boolean> inventoryEnabledSetting) {
        this.antennas = antennas;
        this.inventoryGroup = inventoryGroup;
        this.inventoryInterval = inventoryInterval;
        this.inventoryEnabledSetting = inventoryEnabledSetting;
        resetForRun();
    }

    EventSource<AntennaTaskResult> completedEvent() {
        return completedEvent;
    }

    @Override
    protected void onRunCompleted(Throwable taskFailure) {
        completedEvent.emit(
                taskFailure == null
                        ? AntennaTaskResult.success()
                        : AntennaTaskResult.failed(taskFailure));
    }

    @Override
    protected void resetForRun() {
        phase = Phase.DECIDE;
        antennaIndex = 0;
        groupIndex = 0;
        groupAttempts = 0;
        groupFailure = null;
        disableFailure = null;
    }

    @Override
    public TaskStep runStep() {
        /*
         * Disable has priority over the remainder of an enable/switch cycle.
         * This is what makes a later OFF request take effect without starting a
         * second task.
         */
        if (!inventoryRequestedEnabled() && isEnableOrSwitchPhase()) {
            beginDisable();
            return TaskStep.again();
        }

        switch (phase) {
            case DECIDE:
                return decide();
            case POWER_ON:
                return powerOn();
            case INITIALIZE:
                return initialize();
            case START_DIRECT:
                return startDirect();
            case START_GROUP:
                return startGroup();
            case SWITCH_STOP:
                return stopCurrentGroupAntenna();
            case SWITCH_START:
                return startNextGroupAntenna();
            case DISABLE_STOP:
                return disableStop();
            case DISABLE_POWER_OFF:
                return disablePowerOff();
            default:
                throw new IllegalStateException("Unsupported inventory task phase " + phase);
        }
    }

    private TaskStep decide() {
        if (!inventoryEnabledSetting.changePending()) {
            return TaskStep.done();
        }

        if (inventoryRequestedEnabled()) {
            antennaIndex = 0;
            phase = Phase.POWER_ON;
        } else {
            beginDisable();
        }
        return TaskStep.again();
    }

    /**
     * Powers one antenna per turn. The physical stabilization wait is the only
     * reason this phase may resume after a delay.
     */
    private TaskStep powerOn() {
        if (antennaIndex >= antennas.size()) {
            return startGroupOrFinishEnable();
        }

        ManagedAntenna antenna = antennas.get(antennaIndex);
        antenna.beginInventoryPreparation();
        antenna.powerOn();

        phase = Phase.INITIALIZE;
        Duration delay = antenna.powerStabilization();
        return delay.isZero() ? TaskStep.again() : TaskStep.after(delay);
    }

    private TaskStep initialize() {
        ManagedAntenna antenna = antennas.get(antennaIndex);
        antenna.initialize();

        if (inventoryGroup.contains(antenna)) {
            nextAntenna();
        } else {
            phase = Phase.START_DIRECT;
        }
        return TaskStep.again();
    }

    private TaskStep startDirect() {
        antennas.get(antennaIndex).startInventory();
        nextAntenna();
        return TaskStep.again();
    }

    private TaskStep startGroupOrFinishEnable() {
        if (inventoryGroup.isEmpty()) {
            return inventoryEnabled();
        }

        prepareGroupStart(0, Phase.START_GROUP);
        return TaskStep.again();
    }

    /**
     * Starts the first available multiplex-group member. A failed member is
     * skipped so another healthy member can still provide inventory.
     */
    private TaskStep startGroup() {
        if (groupAttempts >= inventoryGroup.size()) {
            if (groupFailure != null) {
                throw groupFailure;
            }
            throw new IllegalStateException("no prepared antenna could start inventory");
        }

        ManagedAntenna candidate = nextGroupCandidate();
        if (!candidate.availableForInventory()) {
            return TaskStep.again();
        }

        try {
            candidate.startInventory();
            return inventoryEnabled();
        } catch (RuntimeException ex) {
            groupFailure = ex;
            return TaskStep.again();
        }
    }

    private TaskStep inventoryEnabled() {
        inventoryEnabledSetting.markApplied(Boolean.TRUE);

        if (inventoryRequestedEnabled() && availableGroupCount() > 1) {
            phase = Phase.SWITCH_STOP;
            return TaskStep.after(inventoryInterval);
        }
        return finishOrContinue();
    }

    /**
     * Multiplexing always stops the current reader before another reader starts.
     * If fewer than two healthy members remain, rotation simply ends.
     */
    private TaskStep stopCurrentGroupAntenna() {
        if (availableGroupCount() < 2) {
            return TaskStep.done();
        }

        int currentIndex = currentGroupInventoryIndex();
        if (currentIndex < 0) {
            prepareGroupStart(groupIndex, Phase.SWITCH_START);
            return TaskStep.again();
        }

        inventoryGroup.get(currentIndex).stopInventory();
        prepareGroupStart((currentIndex + 1) % inventoryGroup.size(), Phase.SWITCH_START);
        return TaskStep.again();
    }

    private TaskStep startNextGroupAntenna() {
        if (groupAttempts >= inventoryGroup.size()) {
            if (groupFailure != null) {
                throw groupFailure;
            }
            return TaskStep.done();
        }

        ManagedAntenna candidate = nextGroupCandidate();
        if (!candidate.availableForInventory()) {
            return TaskStep.again();
        }

        try {
            candidate.startInventory();
            phase = Phase.SWITCH_STOP;
            return TaskStep.after(inventoryInterval);
        } catch (RuntimeException ex) {
            groupFailure = ex;
            return TaskStep.again();
        }
    }

    private void beginDisable() {
        antennaIndex = antennas.size() - 1;
        disableFailure = null;
        phase = Phase.DISABLE_STOP;
    }

    /**
     * Disable continues through every antenna even when one stop/power-off
     * operation fails. The first failure is reported after cleanup has finished.
     */
    private TaskStep disableStop() {
        if (antennaIndex < 0) {
            return finishDisable();
        }

        try {
            antennas.get(antennaIndex).stopInventory();
        } catch (RuntimeException ex) {
            rememberDisableFailure(ex);
        }

        phase = Phase.DISABLE_POWER_OFF;
        return TaskStep.again();
    }

    private TaskStep disablePowerOff() {
        try {
            antennas.get(antennaIndex).powerOff();
        } catch (RuntimeException ex) {
            rememberDisableFailure(ex);
        }

        antennaIndex--;
        phase = Phase.DISABLE_STOP;
        return antennaIndex < 0 ? finishDisable() : TaskStep.again();
    }

    private TaskStep finishDisable() {
        if (disableFailure != null) {
            throw disableFailure;
        }

        inventoryEnabledSetting.markApplied(Boolean.FALSE);
        return finishOrContinue();
    }

    private TaskStep finishOrContinue() {
        if (inventoryEnabledSetting.changePending()) {
            phase = Phase.DECIDE;
            return TaskStep.again();
        }
        return TaskStep.done();
    }

    private void nextAntenna() {
        antennaIndex++;
        phase = Phase.POWER_ON;
    }

    private void prepareGroupStart(int startIndex, Phase nextPhase) {
        groupIndex = startIndex;
        groupAttempts = 0;
        groupFailure = null;
        phase = nextPhase;
    }

    private ManagedAntenna nextGroupCandidate() {
        ManagedAntenna candidate = inventoryGroup.get(groupIndex);
        groupIndex = (groupIndex + 1) % inventoryGroup.size();
        groupAttempts++;
        return candidate;
    }

    private boolean inventoryRequestedEnabled() {
        return Boolean.TRUE.equals(inventoryEnabledSetting.requestedValue());
    }

    private boolean isEnableOrSwitchPhase() {
        switch (phase) {
            case POWER_ON:
            case INITIALIZE:
            case START_DIRECT:
            case START_GROUP:
            case SWITCH_STOP:
            case SWITCH_START:
                return true;
            default:
                return false;
        }
    }

    private int currentGroupInventoryIndex() {
        for (int index = 0; index < inventoryGroup.size(); index++) {
            if (inventoryGroup.get(index).inventoryRunning()) {
                return index;
            }
        }
        return -1;
    }

    private int availableGroupCount() {
        int count = 0;
        for (ManagedAntenna antenna : inventoryGroup) {
            if (antenna.availableForInventory()) {
                count++;
            }
        }
        return count;
    }

    private void rememberDisableFailure(RuntimeException current) {
        if (disableFailure == null) {
            disableFailure = current;
        } else if (disableFailure != current) {
            disableFailure.addSuppressed(current);
        }
    }
}
