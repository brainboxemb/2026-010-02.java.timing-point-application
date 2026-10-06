package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task.AntennaTasks;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/** Public lifecycle, inventory intent and status boundary for one antenna set. */
public final class AntennaManager {
    private static final Logger LOG = LoggerFactory.getLogger(AntennaManager.class);

    private final ScheduledTaskRunner taskRunner;
    private final ManagedAntennaSet antennaSet;
    private final Setting<Boolean> inventoryEnabledSetting = new Setting<Boolean>(Boolean.FALSE);
    private final AntennaTasks antennaTasks;

    private volatile State state = State.NEW;
    private volatile Throwable failure;


    public AntennaManager(
            List<AntennaInstallation> installations,
            SerialScheduledExecutor controlLane,
            Duration controlTimeout) {
        checkArgument(controlLane != null, "controlLane must not be null");

        taskRunner = new ScheduledTaskRunner(controlLane, controlTimeout);
        antennaSet = new ManagedAntennaSet(installations);
        antennaTasks = new AntennaTasks(
                antennaSet.antennas(),
                antennaSet.inventoryGroup(),
                antennaSet.hasInventoryGroup() ? antennaSet.inventoryInterval() : null,
                inventoryEnabledSetting);

        antennaTasks.selfTestCompletedEvent().subscribe(this::onSelfTestCompleted);
        antennaTasks.inventoryCompletedEvent().subscribe(this::onInventoryCompleted);
    }

    public synchronized void activate() {
        checkState(state == State.NEW || state == State.INACTIVE, "AntennaManager cannot activate from %s", state);

        taskRunner.start();
        state = State.ACTIVE;
        failure = null;

        antennaTasks.startSelfTest(taskRunner);

        LOG.info("AntennaManager activated with {} configured antenna(s)", antennaSet.size());
    }

    public boolean isBusy() {
        return antennaTasks.selfTestRunning() || inventoryEnabledSetting.changePending();
    }

    public boolean isReady() {
        return state == State.ACTIVE
                && !antennaTasks.selfTestRunning()
                && antennaSet.allSelfTestsPassed();
    }

    /**
     * Requests inventory to become enabled.
     *
     * @return false when the manager cannot accept the request
     */
    public boolean requestEnableInventory() {
        return requestInventory(true);
    }

    /**
     * Requests inventory to become disabled.
     *
     * @return false when the manager cannot accept the request
     */
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

            CompletableFuture<Void> shutdown = taskRunner.runTask(antennaTasks.shutdown());
            taskRunner.await(shutdown);
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

        if (shutdownFailure == null) {
            inventoryEnabledSetting.markApplied(Boolean.FALSE);
            state = State.INACTIVE;
            LOG.info("AntennaManager deactivated");
        } else {
            failure = shutdownFailure;
            state = State.FAILED;
            throw shutdownFailure;
        }
    }

    private boolean requestInventory(boolean enabled) {
        if (state != State.ACTIVE) {
            return false;
        }

        inventoryEnabledSetting.request(Boolean.valueOf(enabled));
        startInventoryTaskIfNeeded();
        return true;
    }

    /**
     * Starts the reusable inventory task when requested state still differs from applied state.
     *
     * <p>This is called both after a new request and after self-test/task completion,
     * because a request may have arrived while the task could not yet run.</p>
     */
    private void startInventoryTaskIfNeeded() {
        if (state != State.ACTIVE
                || !antennaSet.allSelfTestsPassed()
                || !inventoryEnabledSetting.changePending()) {
            return;
        }

        antennaTasks.startInventory(taskRunner);
    }

    private void onInventoryCompleted(AntennaTasks.TaskResult result) {
        if (!result.successful()) {
            recordFailure(result.failure());
            LOG.warn("Antenna inventory task failed", result.failure());
        }

        if (state == State.ACTIVE && inventoryEnabledSetting.changePending()) {
            startInventoryTaskIfNeeded();
        }
    }

    private void onSelfTestCompleted(AntennaTasks.TaskResult result) {
        if (state != State.ACTIVE) {
            return;
        }

        if (!result.successful()) {
            recordFailure(result.failure());
        }

        boolean passed = antennaSet.allSelfTestsPassed();
        LOG.info("AntennaManager self-test {}", passed ? "PASS" : "FAIL");

        if (passed) {
            startInventoryTaskIfNeeded();
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
