package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * Lifecycle and control state machine for one antenna set.
 *
 * <p>External calls and task-completion events only provide new input. The
 * manager makes all follow-up decisions in {@link #advanceStateMachine()}, so
 * task handlers do not each implement their own transition rules.</p>
 */
public final class AntennaManager {
    private static final Logger LOG = LoggerFactory.getLogger(AntennaManager.class);

    /**
     * Internal control phase while the public lifecycle remains ACTIVE.
     *
     * <p>IDLE means that startup self-test passed and no manager task is
     * currently running. Physical inventory may still be active after an
     * enable task completed.</p>
     */
    private enum Phase {
        STOPPED,
        SELF_TEST,
        IDLE,
        INVENTORY_TASK,
        SELF_TEST_FAILED,
        INVENTORY_FAILED
    }

    private final ScheduledTaskRunner taskRunner;
    private final AntennaSet antennaSet;
    private final Setting<Boolean> inventoryEnabledSetting = new Setting<Boolean>(Boolean.FALSE);
    private final AntennaTasks antennaTasks;

    private volatile State state = State.NEW;
    private volatile Phase phase = Phase.STOPPED;
    private volatile Throwable failure;

    private AntennaTasks.TaskResult selfTestResult;
    private AntennaTasks.TaskResult inventoryResult;

    public AntennaManager(
            AntennaSet antennaSet,
            SerialScheduledExecutor controlLane,
            Duration controlTimeout) {
        checkArgument(antennaSet != null, "antennaSet must not be null");
        checkArgument(controlLane != null, "controlLane must not be null");

        antennaSet.seal();
        this.antennaSet = antennaSet;
        taskRunner = new ScheduledTaskRunner(controlLane, controlTimeout);
        antennaTasks = new AntennaTasks(
                antennaSet.antennas(),
                antennaSet.inventoryGroup(),
                antennaSet.hasInventoryGroup() ? antennaSet.inventoryInterval() : null,
                inventoryEnabledSetting);

        /*
         * Task events are wired once during composition. The callbacks only
         * store the completed result and advance this manager state machine.
         */
        antennaTasks.selfTestCompletedEvent().subscribe(this::onSelfTestCompleted);
        antennaTasks.inventoryCompletedEvent().subscribe(this::onInventoryCompleted);
    }

    public synchronized void activate() {
        checkState(state == State.NEW || state == State.INACTIVE, "AntennaManager cannot activate from %s", state);

        taskRunner.start();
        state = State.ACTIVE;
        phase = Phase.SELF_TEST;
        failure = null;
        selfTestResult = null;
        inventoryResult = null;

        antennaTasks.startSelfTest(taskRunner);
        LOG.info("AntennaManager activated with {} configured antenna(s)", antennaSet.size());
    }

    public boolean isBusy() {
        return phase == Phase.SELF_TEST || inventoryEnabledSetting.changePending();
    }

    public boolean isReady() {
        return state == State.ACTIVE
                && phase != Phase.SELF_TEST
                && phase != Phase.SELF_TEST_FAILED
                && antennaSet.allSelfTestsPassed();
    }

    /** Requests inventory to become enabled. */
    public boolean requestEnableInventory() {
        return requestInventory(true);
    }

    /** Requests inventory to become disabled. */
    public boolean requestDisableInventory() {
        return requestInventory(false);
    }

    public EventSource<TagObservation> tagObservedEvent(AntennaId antennaId) {
        return antennaSet.tagObservedEvent(antennaId);
    }

    public State state() {
        return state;
    }

    public Throwable failure() {
        return failure;
    }

    public List<AntennaStatus> statuses() {
        return antennaSet.statuses();
    }

    public AntennaStatus status(AntennaId antennaId) {
        return antennaSet.status(antennaId);
    }

    public void deactivate() {
        synchronized (this) {
            if (state == State.INACTIVE) {
                return;
            }

            checkState(state != State.DEACTIVATING, "AntennaManager is already deactivating");
            state = State.DEACTIVATING;
        }

        inventoryEnabledSetting.request(Boolean.FALSE);
        antennaTasks.cancelSelfTest();
        antennaTasks.cancelInventory();

        RuntimeException shutdownFailure = null;
        try {
            if (taskRunner.isNew()) {
                taskRunner.start();
            }
            antennaTasks.shutdownAndWait(taskRunner);
        } catch (RuntimeException ex) {
            shutdownFailure = ex;
        }

        try {
            taskRunner.close();
        } catch (RuntimeException ex) {
            if (shutdownFailure == null) {
                shutdownFailure = ex;
            } else if (shutdownFailure != ex) {
                shutdownFailure.addSuppressed(ex);
            }
        }

        synchronized (this) {
            selfTestResult = null;
            inventoryResult = null;

            if (shutdownFailure == null) {
                inventoryEnabledSetting.markApplied(Boolean.FALSE);
                phase = Phase.STOPPED;
                state = State.INACTIVE;
                LOG.info("AntennaManager deactivated");
            } else {
                failure = shutdownFailure;
                phase = Phase.STOPPED;
                state = State.FAILED;
            }
        }

        if (shutdownFailure != null) {
            throw shutdownFailure;
        }
    }

    private synchronized boolean requestInventory(boolean enabled) {
        if (state != State.ACTIVE) {
            return false;
        }

        inventoryEnabledSetting.request(Boolean.valueOf(enabled));

        /*
         * A fresh request is the explicit retry trigger after an inventory task
         * failure. Self-test failure is not retried by an inventory request.
         */
        if (phase == Phase.INVENTORY_FAILED) {
            phase = Phase.IDLE;
        }

        advanceStateMachine();
        return true;
    }

    private synchronized void onSelfTestCompleted(AntennaTasks.TaskResult result) {
        selfTestResult = result;
        advanceStateMachine();
    }

    private synchronized void onInventoryCompleted(AntennaTasks.TaskResult result) {
        inventoryResult = result;
        advanceStateMachine();
    }

    /**
     * Owns all manager transitions while the component is active.
     *
     * <p>A phase either starts one child task and waits for its completion event,
     * or advances immediately to the next stable phase. Task handlers never
     * decide independently which task should run next.</p>
     */
    private void advanceStateMachine() {
        if (state != State.ACTIVE) {
            return;
        }

        while (true) {
            switch (phase) {
                case SELF_TEST:
                    if (selfTestResult == null) {
                        return;
                    }
                    completeSelfTest();
                    break;

                case IDLE:
                    if (!inventoryEnabledSetting.changePending()) {
                        return;
                    }
                    phase = Phase.INVENTORY_TASK;
                    inventoryResult = null;
                    antennaTasks.startInventory(taskRunner);
                    return;

                case INVENTORY_TASK:
                    if (inventoryResult == null) {
                        return;
                    }
                    completeInventoryTask();
                    break;

                case SELF_TEST_FAILED:
                case INVENTORY_FAILED:
                case STOPPED:
                    return;

                default:
                    throw new IllegalStateException("Unsupported AntennaManager phase " + phase);
            }
        }
    }

    private void completeSelfTest() {
        AntennaTasks.TaskResult result = selfTestResult;
        selfTestResult = null;

        if (!result.successful() || !antennaSet.allSelfTestsPassed()) {
            recordFailure(result.failure());
            phase = Phase.SELF_TEST_FAILED;
            LOG.warn("AntennaManager self-test FAIL");
            return;
        }

        phase = Phase.IDLE;
        LOG.info("AntennaManager self-test PASS");
    }

    private void completeInventoryTask() {
        AntennaTasks.TaskResult result = inventoryResult;
        inventoryResult = null;

        if (!result.successful()) {
            recordFailure(result.failure());
            phase = Phase.INVENTORY_FAILED;
            LOG.warn("Antenna inventory task failed", result.failure());
            return;
        }

        phase = Phase.IDLE;
    }

    private void recordFailure(Throwable cause) {
        if (cause == null || cause instanceof CancellationException) {
            return;
        }

        if (failure == null) {
            failure = cause;
        }

        if (taskRunner.failure() != null) {
            state = State.FAILED;
        }
    }
}
