package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

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
public final class AntennaManager implements CooperativeTask {
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
        START_SELF_TEST,
        WAIT_SELF_TEST,
        IDLE,
        START_INVENTORY,
        WAIT_INVENTORY,
        SELF_TEST_FAILED,
        INVENTORY_FAILED
    }

    private final ScheduledTaskRunner taskRunner;
    private final AntennaSet antennaSet;
    private final Setting<Boolean> inventoryEnabledSetting = new Setting<Boolean>(Boolean.FALSE);
    private final SelfTestTask selfTestTask;
    private final InventoryTask inventoryTask;
    private final AntennaShutdownTask shutdownTask;

    private volatile State state = State.NEW;
    private volatile Phase phase = Phase.STOPPED;
    private volatile Throwable failure;

    /*
     * Execution handle of the AntennaManager state machine itself. Child-task
     * Futures remain owned by those tasks.
     */
    private CompletableFuture<Void> stateMachineOperation;
    private boolean stateMachineRunRequested;
    private boolean inventoryRequestReceived;

    private volatile AntennaTaskResult selfTestResult;
    private volatile AntennaTaskResult inventoryResult;

    public AntennaManager(
            AntennaSet antennaSet,
            SerialScheduledExecutor controlLane,
            Duration controlTimeout) {
        checkArgument(antennaSet != null, "antennaSet must not be null");
        checkArgument(controlLane != null, "controlLane must not be null");

        antennaSet.seal();
        this.antennaSet = antennaSet;
        taskRunner = new ScheduledTaskRunner(controlLane, controlTimeout);
        selfTestTask = new SelfTestTask(antennaSet.antennas());
        inventoryTask = new InventoryTask(
                antennaSet.antennas(),
                antennaSet.inventoryGroup(),
                antennaSet.hasInventoryGroup() ? antennaSet.inventoryInterval() : null,
                inventoryEnabledSetting);
        shutdownTask = new AntennaShutdownTask(antennaSet.antennas());

        /*
         * Task events are wired once during composition. The callbacks only
         * store the completed result and advance this manager state machine.
         */
        selfTestTask.completedEvent().subscribe(this::onSelfTestCompleted);
        inventoryTask.completedEvent().subscribe(this::onInventoryCompleted);
    }

    public synchronized void activate() {
        checkState(state == State.NEW || state == State.INACTIVE, "AntennaManager cannot activate from %s", state);

        taskRunner.start();
        state = State.ACTIVE;
        phase = Phase.START_SELF_TEST;
        failure = null;
        selfTestResult = null;
        inventoryResult = null;
        inventoryRequestReceived = false;

        requestStateMachineRun();
        LOG.info("AntennaManager activated with {} configured antenna(s)", antennaSet.size());
    }

    public boolean isBusy() {
        Phase current = phase;
        return current == Phase.START_SELF_TEST
                || current == Phase.WAIT_SELF_TEST
                || inventoryEnabledSetting.changePending();
    }

    public boolean isReady() {
        Phase current = phase;
        return state == State.ACTIVE
                && current != Phase.START_SELF_TEST
                && current != Phase.WAIT_SELF_TEST
                && current != Phase.SELF_TEST_FAILED
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
        cancelStateMachine();
        selfTestTask.cancel();
        inventoryTask.cancel();

        RuntimeException shutdownFailure = null;
        try {
            if (taskRunner.isNew()) {
                taskRunner.start();
            }
            shutdownTask.runAndWait(taskRunner);
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
            stateMachineRunRequested = false;
            inventoryRequestReceived = false;

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
        inventoryRequestReceived = true;
        requestStateMachineRun();
        return true;
    }

    private synchronized void onSelfTestCompleted(AntennaTaskResult result) {
        selfTestResult = result;
        requestStateMachineRun();
    }

    private synchronized void onInventoryCompleted(AntennaTaskResult result) {
        inventoryResult = result;
        requestStateMachineRun();
    }

    /**
     * Runs one AntennaManager control transition on the manager serial lane.
     *
     * <p>The task stops whenever it must wait for a child task or external
     * request. That later input requests another run of this same state machine.</p>
     */
    @Override
    public TaskStep runStep() {
        if (state != State.ACTIVE) {
            return TaskStep.done();
        }

        switch (phase) {
            case START_SELF_TEST:
                selfTestResult = null;
                selfTestTask.start(taskRunner);
                phase = Phase.WAIT_SELF_TEST;
                return TaskStep.done();

            case WAIT_SELF_TEST:
                return handleSelfTestCompletion();

            case IDLE:
                inventoryRequestReceived = false;
                if (!inventoryEnabledSetting.changePending()) {
                    return TaskStep.done();
                }
                phase = Phase.START_INVENTORY;
                return TaskStep.again();

            case START_INVENTORY:
                inventoryResult = null;
                inventoryTask.start(taskRunner);
                phase = Phase.WAIT_INVENTORY;
                return TaskStep.done();

            case WAIT_INVENTORY:
                return handleInventoryCompletion();

            case INVENTORY_FAILED:
                if (!inventoryRequestReceived) {
                    return TaskStep.done();
                }
                inventoryRequestReceived = false;
                phase = Phase.IDLE;
                return TaskStep.again();

            case SELF_TEST_FAILED:
            case STOPPED:
                return TaskStep.done();

            default:
                throw new IllegalStateException("Unsupported AntennaManager phase " + phase);
        }
    }

    private TaskStep handleSelfTestCompletion() {
        AntennaTaskResult result = selfTestResult;
        if (result == null) {
            return TaskStep.done();
        }
        selfTestResult = null;

        if (!result.successful() || !antennaSet.allSelfTestsPassed()) {
            recordFailure(result.failure());
            phase = Phase.SELF_TEST_FAILED;
            LOG.warn("AntennaManager self-test FAIL");
            return TaskStep.done();
        }

        phase = Phase.IDLE;
        LOG.info("AntennaManager self-test PASS");
        return TaskStep.again();
    }

    private TaskStep handleInventoryCompletion() {
        AntennaTaskResult result = inventoryResult;
        if (result == null) {
            return TaskStep.done();
        }
        inventoryResult = null;

        if (!result.successful()) {
            recordFailure(result.failure());
            phase = Phase.INVENTORY_FAILED;
            LOG.warn("Antenna inventory task failed", result.failure());
            return TaskStep.again();
        }

        phase = Phase.IDLE;
        return TaskStep.again();
    }

    /**
     * Coalesces multiple external/event wake-ups into one manager-task run.
     *
     * <p>A wake-up that arrives while the task is still completing is remembered
     * and starts a new run from the completion callback, avoiding a lost event.</p>
     */
    private synchronized void requestStateMachineRun() {
        stateMachineRunRequested = true;

        if (state != State.ACTIVE || stateMachineOperation != null && !stateMachineOperation.isDone()) {
            return;
        }

        stateMachineRunRequested = false;
        stateMachineOperation = taskRunner.runTask(this);
        stateMachineOperation.whenComplete(this::onStateMachineRunCompleted);
    }

    private synchronized void onStateMachineRunCompleted(Void ignored, Throwable taskFailure) {
        stateMachineOperation = null;

        if (taskFailure != null && !(taskFailure instanceof CancellationException)) {
            recordFailure(taskFailure);
            state = State.FAILED;
            LOG.warn("AntennaManager state machine failed", taskFailure);
            return;
        }

        if (stateMachineRunRequested && state == State.ACTIVE) {
            requestStateMachineRun();
        }
    }

    private synchronized void cancelStateMachine() {
        stateMachineRunRequested = false;
        if (stateMachineOperation != null && !stateMachineOperation.isDone()) {
            stateMachineOperation.cancel(true);
        }
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
