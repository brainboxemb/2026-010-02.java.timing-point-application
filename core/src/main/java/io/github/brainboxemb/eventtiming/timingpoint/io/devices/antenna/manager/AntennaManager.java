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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * Lifecycle and control state machine for one antenna set.
 *
 * <p>External calls and task-completion events only wake this state machine.
 * Task results remain owned by the tasks that produced them; manager transitions
 * read that authoritative task state in {@link #runStep()}.</p>
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
     * The manager state machine is started only when there is new input.
     *
     * stateMachineRunning prevents two runs of this same state machine from
     * being admitted concurrently.
     *
     * stateMachineWakePending remembers an input that arrives while a run is
     * still active. The completion callback starts one new run afterwards.
     * Multiple inputs may therefore coalesce into one run without losing the
     * fact that the state machine must look at its current state again.
     *
     * No Future is stored here: the Future belongs only to the execution
     * mechanism. The manager needs these two state facts, not the Future itself.
     */
    private boolean stateMachineRunning;
    private boolean stateMachineWakePending;

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
         * wake this manager state machine; each task keeps its own result.
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
        requestStateMachineRun();
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
            stateMachineRunning = false;
            stateMachineWakePending = false;

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
        requestStateMachineRun();
        return true;
    }

    /**
     * Completion events only wake the manager. The result itself remains with
     * the task and is read from that task when WAIT_SELF_TEST is processed.
     */
    private void onSelfTestCompleted(AntennaTaskResult ignored) {
        requestStateMachineRun();
    }

    /**
     * Completion events only wake the manager. The result itself remains with
     * the task and is read from that task when WAIT_INVENTORY is processed.
     */
    private void onInventoryCompleted(AntennaTaskResult ignored) {
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
                selfTestTask.start(taskRunner);
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
                inventoryTask.start(taskRunner);
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

    /**
     * Wakes the manager state machine.
     *
     * <p>Only one run may be active at a time. If input arrives during that run,
     * wakePending remains true. When the run completes, one new run is admitted.
     * The state machine always reads current manager state, so ten wake-ups while
     * running do not require ten queued executions.</p>
     */
    private synchronized void requestStateMachineRun() {
        stateMachineWakePending = true;

        if (state != State.ACTIVE || stateMachineRunning) {
            return;
        }

        stateMachineWakePending = false;
        stateMachineRunning = true;

        /*
         * The Future is deliberately not stored. It is used only here to learn
         * when this particular run has finished, so we can clear the running
         * flag and honour a wake-up that arrived in the meantime.
         */
        taskRunner.runTask(this).whenComplete(this::onStateMachineRunCompleted);
    }

    /**
     * Execution callback for one completed manager-task run.
     *
     * <p>This does not contain manager transition logic; it only maintains the
     * scheduling state of the manager task and starts a pending wake-up.</p>
     */
    private synchronized void onStateMachineRunCompleted(Void ignored, Throwable taskFailure) {
        stateMachineRunning = false;

        if (taskFailure != null && !(taskFailure instanceof CancellationException)) {
            recordFailure(taskFailure);
            state = State.FAILED;
            LOG.warn("AntennaManager state machine failed", taskFailure);
            return;
        }

        if (stateMachineWakePending && state == State.ACTIVE) {
            requestStateMachineRun();
        }
    }

    /**
     * Prevents a pending wake-up from starting another manager run during
     * deactivation. An already admitted run is intentionally not cancelled:
     * its next short step observes DEACTIVATING and returns DONE before the
     * shutdown task can run on the same serial lane.
     */
    private synchronized void cancelStateMachine() {
        stateMachineWakePending = false;
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
