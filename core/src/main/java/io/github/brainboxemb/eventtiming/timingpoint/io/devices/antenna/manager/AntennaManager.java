package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.RunnableSerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.TaskSerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.util.List;
import java.util.concurrent.CancellationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * Lifecycle and control state machine for one antenna set.
 *
 * <p>External calls and task-completion events only wake this state machine.
 * Task results remain owned by the tasks that produced them; manager transitions
 * read that authoritative task state in {@link #runStep()}.</p>
 */
public final class AntennaManager extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(AntennaManager.class);

    /** Self-test completion input; the payload only wakes reconciliation. */
    private static final EventInput<AntennaManager, AntennaTaskResult> SELF_TEST_COMPLETED =
            EventInput.of(
                    (manager, source) -> source.subscribe(manager::onSelfTestCompleted));

    /** Inventory-task completion input; the payload only wakes reconciliation. */
    private static final EventInput<AntennaManager, AntennaTaskResult> INVENTORY_COMPLETED =
            EventInput.of(
                    (manager, source) -> source.subscribe(manager::onInventoryCompleted));

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
        INVENTORY_FAILED
    }

    private final TaskSerialLane taskLane;
    private final AntennaSet antennaSet;
    private final Setting<Boolean> inventoryEnabledSetting =
            new Setting<Boolean>(Boolean.FALSE);
    private final SelfTestTask selfTestTask;
    private final InventoryTask inventoryTask;
    private final AntennaShutdownTask shutdownTask;

    private volatile State state = State.NEW;
    private volatile Phase phase = Phase.STOPPED;
    private volatile Throwable failure;

    /** Cooperative main task for the AntennaManager control state. */
    private final MainTask mainTask = new MainTask();

    public AntennaManager(
            AntennaSet antennaSet,
            TaskSerialLane taskLane) {
        failIfInvalidArgument(antennaSet != null, "antennaSet must not be null");
        failIfInvalidArgument(taskLane != null, "taskLane must not be null");

        antennaSet.seal();
        this.antennaSet = antennaSet;
        this.taskLane = taskLane;
        selfTestTask = new SelfTestTask(antennaSet.antennas());
        inventoryTask = new InventoryTask(
                antennaSet.antennas(),
                antennaSet.inventoryGroup(),
                antennaSet.hasInventoryGroup() ? antennaSet.inventoryInterval() : null,
                inventoryEnabledSetting);
        shutdownTask = new AntennaShutdownTask(antennaSet.antennas());

        /*
         * Task events are wired once during composition. The callbacks only
         * wake this manager state machine; each task keeps its own result.
         */
        SELF_TEST_COMPLETED.bind(this, selfTestTask.COMPLETED);
        INVENTORY_COMPLETED.bind(this, inventoryTask.COMPLETED);
    }

    @Override
    protected synchronized void onActivate() {
        failIfIllegalState(state == State.NEW || state == State.INACTIVE, "AntennaManager cannot activate from %s", state);

        taskLane.start();
        state = State.ACTIVE;
        phase = Phase.START_SELF_TEST;
        failure = null;
        mainTask.wake(taskLane);
    }

    public boolean isBusy() {
        /*
         * Busy means work is currently executing, not merely that requested
         * state differs from applied state after a failed attempt.
         */
        return selfTestTask.isRunning()
                || inventoryTask.isRunning() && inventoryEnabledSetting.changePending();
    }

    public boolean isReady() {
        Phase current = phase;
        return state == State.ACTIVE
                && current != Phase.START_SELF_TEST
                && current != Phase.WAIT_SELF_TEST;
    }

    /**
     * Sets the desired system inventory state without turning repeated state into a retry.
     *
     * <p>The call is non-blocking. It records the desired value immediately and only wakes
     * AntennaManager's own task when reconciliation is needed. Desired state may be set
     * before activation; activation will reconcile it after startup self-test.</p>
     */
    public synchronized void setInventoryRequired(boolean required) {
        if (!inventoryEnabledSetting.requestIfChanged(Boolean.valueOf(required))) {
            return;
        }

        if (state == State.ACTIVE) {
            mainTask.wake(taskLane);
        }
    }

    /**
     * Explicitly requests inventory to become enabled.
     *
     * <p>Unlike re-publishing the same desired setting, repeating this call is
     * an explicit new retry request.</p>
     */
    public boolean requestEnableInventory() {
        return requestInventory(true);
    }

    /** Requests inventory to become disabled. */
    public boolean requestDisableInventory() {
        return requestInventory(false);
    }

    /** Returns configured antenna ids in composition order. */
    public List<AntennaId> antennaIds() {
        return antennaSet.antennaIds();
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

    @Override
    protected void onDeactivate() {
        synchronized (this) {
            if (state == State.INACTIVE) {
                return;
            }

            failIfIllegalState(state != State.DEACTIVATING, "AntennaManager is already deactivating");
            state = State.DEACTIVATING;
        }

        inventoryEnabledSetting.request(Boolean.FALSE);
        mainTask.cancel();
        selfTestTask.cancel();
        inventoryTask.cancel();

        RuntimeException shutdownFailure = null;
        try {
            if (taskLane.state() == RunnableSerialLane.State.NEW) {
                taskLane.start();
            }
            shutdownTask.runAndWait(taskLane);
        } catch (RuntimeException ex) {
            shutdownFailure = ex;
        }

        try {
            taskLane.close();
        } catch (RuntimeException ex) {
            if (shutdownFailure == null) {
                shutdownFailure = ex;
            } else if (shutdownFailure != ex) {
                shutdownFailure.addSuppressed(ex);
            }
        }

        synchronized (this) {
            if (shutdownFailure == null) {
                inventoryEnabledSetting.markApplied(Boolean.FALSE);
                phase = Phase.STOPPED;
                state = State.INACTIVE;
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
        mainTask.wake(taskLane);
        return true;
    }

    /**
     * Completion events only wake the manager. The result itself remains with
     * the task and is read from that task when WAIT_SELF_TEST is processed.
     */
    private void onSelfTestCompleted(AntennaTaskResult ignored) {
        mainTask.wake(taskLane);
    }

    /**
     * Completion events only wake the manager. The result itself remains with
     * the task and is read from that task when WAIT_INVENTORY is processed.
     */
    private void onInventoryCompleted(AntennaTaskResult ignored) {
        mainTask.wake(taskLane);
    }

    /**
     * Runs one AntennaManager control transition on the manager serial lane.
     *
     * <p>The task stops whenever it must wait for a child task or external
     * request. That later input requests another run of this same state machine.</p>
     */
    private TaskStep runMainTask() {
        if (state != State.ACTIVE) {
            return TaskStep.done();
        }

        switch (phase) {
            case START_SELF_TEST:
                selfTestTask.start(taskLane);
                phase = Phase.WAIT_SELF_TEST;
                return TaskStep.done();

            case WAIT_SELF_TEST:
                return handleSelfTestCompletion();

            case IDLE:
                if (!inventoryEnabledSetting.changePending()) {
                    return TaskStep.done();
                }
                phase = Phase.START_INVENTORY;
                return TaskStep.again();

            case START_INVENTORY:
                inventoryTask.start(taskLane);
                phase = Phase.WAIT_INVENTORY;
                return TaskStep.done();

            case WAIT_INVENTORY:
                return handleInventoryCompletion();

            case INVENTORY_FAILED:
                if (!inventoryTask.hasNewRequestSinceLastRun()) {
                    return TaskStep.done();
                }
                phase = Phase.START_INVENTORY;
                return TaskStep.again();

            case STOPPED:
                return TaskStep.done();

            default:
                throw new IllegalStateException("Unsupported AntennaManager phase " + phase);
        }
    }

    private TaskStep handleSelfTestCompletion() {
        AntennaTaskResult result = selfTestTask.lastResult();
        if (result == null) {
            return TaskStep.done();
        }

        phase = Phase.IDLE;

        if (result.successful() && antennaSet.allSelfTestsPassed()) {
            LOG.info("Antenna startup self-test complete");
        } else {
            /*
             * Startup self-test is diagnostic. A failed result remains visible
             * per antenna but does not block a later inventory attempt.
             */
            LOG.warn("Antenna startup self-test complete with one or more FAIL results");
        }

        return TaskStep.again();
    }

    private TaskStep handleInventoryCompletion() {
        AntennaTaskResult result = inventoryTask.lastResult();
        if (result == null) {
            return TaskStep.done();
        }

        if (!result.successful()) {
            recordFailure(result.failure());
            phase = Phase.INVENTORY_FAILED;
            LOG.warn("Antenna inventory task failed", result.failure());
            return TaskStep.again();
        }

        phase = Phase.IDLE;
        return TaskStep.again();
    }

    /** Handles execution failure of the manager main task. */
    private synchronized void onStateMachineFailure(
            Throwable taskFailure) {
        recordFailure(
                taskFailure);
        state = State.FAILED;
        LOG.warn(
                "AntennaManager state machine failed",
                taskFailure);
    }

    private final class MainTask extends AbstractTask {

        @Override
        protected void resetForRun() {
            // Main task state is owned by AntennaManager.
        }

        @Override
        public TaskStep runStep() {
            return runMainTask();
        }

        @Override
        protected void onRunCompleted(Throwable failure) {
            if (failure != null) {
                onStateMachineFailure(failure);
            }
        }
    }

    private void recordFailure(Throwable cause) {
        if (cause == null || cause instanceof CancellationException) {
            return;
        }

        if (failure == null) {
            failure = cause;
        }

        if (taskLane.failure() != null) {
            state = State.FAILED;
        }
    }
}
