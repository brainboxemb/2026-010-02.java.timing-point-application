package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaSelfTestResult;
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

/**
 * Public lifecycle, inventory-intent and status boundary for one antenna set.
 *
 * <p>The manager starts antenna self-tests and one reusable inventory task.
 * Device sequencing remains inside antenna/task objects; completion returns as
 * events or task completion on the manager serial lane.</p>
 */
public final class AntennaManager {
    private static final Logger LOG = LoggerFactory.getLogger(AntennaManager.class);

    private final ScheduledTaskRunner taskRunner;
    private final ManagedAntennaSet antennaSet;
    private final Setting<Boolean> inventoryEnabledSetting = new Setting<Boolean>(Boolean.FALSE);
    private final AntennaTasks antennaTasks;

    private volatile State state = State.NEW;
    private volatile Throwable failure;
    private volatile boolean selfTestPassed;
    private volatile int pendingSelfTests;

    private CompletableFuture<Void> inventoryOperation;

    public AntennaManager(
            List<AntennaInstallation> installations,
            SerialScheduledExecutor controlLane,
            Duration controlTimeout) {
        checkArgument(controlLane != null, "controlLane must not be null");

        taskRunner = new ScheduledTaskRunner(controlLane, controlTimeout);
        antennaSet = new ManagedAntennaSet(installations, taskRunner);
        antennaTasks =
                new AntennaTasks(
                        antennaSet.antennas(),
                        antennaSet.inventoryGroup(),
                        antennaSet.hasInventoryGroup()
                                ? antennaSet.inventoryInterval()
                                : null,
                        inventoryEnabledSetting);

        for (ManagedAntenna antenna : antennaSet.antennas()) {
            antenna.selfTestCompletedEvent()
                    .subscribe(
                            this::onSelfTestCompleted);
        }
    }

    public synchronized void activate() {
        checkState(state == State.NEW || state == State.INACTIVE, "AntennaManager cannot activate from %s", state);

        taskRunner.start();
        state = State.ACTIVE;
        selfTestPassed = false;
        pendingSelfTests = antennaSet.size();

        LOG.info("AntennaManager activated with {} configured antenna(s)", antennaSet.size());

        for (ManagedAntenna antenna : antennaSet.antennas()) {
            if (!antenna.startSelfTest()) {
                recordFailure(
                        new IllegalStateException(
                                "Antenna "
                                        + antenna.antennaId()
                                        + " rejected self-test start"));
                pendingSelfTests--;
            }
        }

        finishSelfTestRoundIfComplete();
    }

    public boolean isBusy() {
        return pendingSelfTests > 0
                || inventoryEnabledSetting.changePending();
    }

    public boolean isReady() {
        return state == State.ACTIVE
                && selfTestPassed;
    }

    public boolean requestEnableInventory() {
        return requestInventory(true);
    }

    public boolean requestDisableInventory() {
        return requestInventory(false);
    }

    public void enableInventory() {
        if (!requestEnableInventory()) {
            throw new IllegalStateException(
                    "AntennaManager rejected enable-inventory request");
        }
    }

    public void disableInventory() {
        if (!requestDisableInventory()) {
            throw new IllegalStateException(
                    "AntennaManager rejected disable-inventory request");
        }
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
        cancel(inventoryOperation);

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
                shutdownFailure.addSuppressed(
                        ex);
            }
        }

        pendingSelfTests = 0;
        inventoryOperation = null;

        if (shutdownFailure == null) {
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

        boolean accepted = taskRunner.execute(this::startInventoryTaskIfNeeded);
        if (!accepted) {
            recordFailure(
                    new IllegalStateException(
                            "AntennaManager control lane rejected inventory request"));
        }
        return accepted;
    }

    /**
     * Starts the single inventory state machine when inventory intent is pending.
     *
     * <p>If it is already running, the task reads the updated Setting on its
     * next turn and changes direction itself.</p>
     */
    private void startInventoryTaskIfNeeded() {
        if (state != State.ACTIVE
                || !selfTestPassed
                || !inventoryEnabledSetting.changePending()
                || inventoryTaskRunning()) {
            return;
        }

        inventoryOperation = taskRunner.runTask(antennaTasks.inventory());
        inventoryOperation.whenComplete(this::onInventoryTaskCompleted);
    }

    /**
     * Re-admits inventory-task completion onto the manager serial lane.
     */
    private void onInventoryTaskCompleted(Void ignored, Throwable taskFailure) {
        taskRunner.execute(() -> inventoryTaskCompleted(taskFailure));
    }

    private void inventoryTaskCompleted(Throwable taskFailure) {
        inventoryOperation = null;

        if (taskFailure != null
                && !(taskFailure instanceof CancellationException)) {
            recordFailure(taskFailure);
            LOG.warn("Antenna inventory task failed", taskFailure);
        }

        if (state == State.ACTIVE
                && inventoryEnabledSetting.changePending()) {
            startInventoryTaskIfNeeded();
        }
    }

    /**
     * Re-admits device completion onto the manager serial lane.
     */
    private void onSelfTestCompleted(AntennaSelfTestResult result) {
        taskRunner.execute(() -> selfTestCompleted(result));
    }

    private void selfTestCompleted(AntennaSelfTestResult result) {
        if (state != State.ACTIVE) {
            return;
        }

        if (!result.passed()) {
            recordFailure(result.failure());
        }

        if (pendingSelfTests > 0) {
            pendingSelfTests--;
        }
        finishSelfTestRoundIfComplete();
    }

    private void finishSelfTestRoundIfComplete() {
        if (pendingSelfTests != 0) {
            return;
        }

        selfTestPassed = antennaSet.allSelfTestsPassed();

        LOG.info("AntennaManager self-test {}", selfTestPassed ? "PASS" : "FAIL");

        if (selfTestPassed) {
            startInventoryTaskIfNeeded();
        }
    }

    private boolean inventoryTaskRunning() {
        return inventoryOperation != null
                && !inventoryOperation.isDone();
    }

    private void recordFailure(Throwable cause) {
        if (cause == null
                || cause instanceof CancellationException) {
            return;
        }
        if (failure == null) {
            failure = cause;
        }
        if (taskRunner.failure() != null) {
            state = State.FAILED;
        }
    }

    private static void cancel(CompletableFuture<Void> operation) {
        if (operation != null
                && !operation.isDone()) {
            operation.cancel(true);
        }
    }
}
