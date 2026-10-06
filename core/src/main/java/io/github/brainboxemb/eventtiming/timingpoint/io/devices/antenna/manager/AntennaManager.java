package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task.AntennaTasks;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
 import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public lifecycle and intent boundary for one configured antenna set.
 *
 * <p>The manager deliberately contains little device sequencing. It owns the
 * requested inventory setting and admits operation-specific cooperative tasks
 * to one serial scheduled control lane. Physical transition steps live in the
 * task classes and in {@link ManagedAntenna}; round-robin selection lives in
 * {@link AntennaSwitchController}.</p>
 */
public final class AntennaManager {
    private static final Logger LOG =
            LoggerFactory.getLogger(AntennaManager.class);

    private final ManagedAntennaSet antennaSet;
    private final ScheduledTaskRunner tasks;

    private final Setting<Boolean> inventoryEnabledSetting =
            new Setting<Boolean>(
                    Boolean.FALSE);

    private volatile State state = State.NEW;
    private volatile Throwable failure;
    private volatile boolean busy;
    private volatile boolean selfTestPassed;

    private CompletableFuture<Void> activeOperation;
    private CompletableFuture<Void> switchingOperation;

    public AntennaManager(
            List<AntennaInstallation> installations,
            SerialScheduledExecutor controlLane,
            Duration controlTimeout) {
        if (controlLane == null) {
            throw new IllegalArgumentException(
                    "controlLane must not be null");
        }

        antennaSet =
                new ManagedAntennaSet(
                        installations);
        tasks =
                new ScheduledTaskRunner(
                        controlLane,
                        controlTimeout);
    }

    /** Activates control and starts the asynchronous startup self-test. */
    public void activate() {
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException(
                        "AntennaManager can only activate from NEW; current state="
                                + state);
            }
        }

        tasks.start();
        state = State.ACTIVE;
        selfTestPassed = false;

        LOG.info(
                "AntennaManager activated with {} configured antenna(s)",
                antennaSet.size());

        startOperation(
                "self-test",
                AntennaTasks.selfTest(
                        antennaSet.antennas()),
                this::selfTestCompleted);
    }

    public boolean isBusy() {
        return busy;
    }

    public boolean isReady() {
        return state == State.ACTIVE
                && selfTestPassed;
    }

    public boolean requestEnableInventory() {
        return requestInventory(
                true);
    }

    public boolean requestDisableInventory() {
        return requestInventory(
                false);
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

    public EventSource<TagObservation> tagObservedEvent(
            AntennaId antennaId) {
        return antennaSet.tagObservedEvent(
                antennaId);
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

    public AntennaStatus status(
            AntennaId antennaId) {
        return antennaSet.status(
                antennaId);
    }

    /**
     * Cancels manager operations, shuts down configured devices and closes the
     * logical control lane. Runtime retains ownership of the physical worker.
     */
    public void deactivate() {
        synchronized (this) {
            if (state == State.INACTIVE) {
                return;
            }
            if (state == State.DEACTIVATING) {
                throw new IllegalStateException(
                        "AntennaManager is already deactivating");
            }
            state = State.DEACTIVATING;
        }

        inventoryEnabledSetting.request(
                Boolean.FALSE);
        cancel(
                switchingOperation);
        cancel(
                activeOperation);

        RuntimeException shutdownFailure = null;
        try {
            if (tasks.isNew()) {
                tasks.start();
            }
            CompletableFuture<Void> shutdown =
                    tasks.runTask(
                            AntennaTasks.shutdown(
                                    antennaSet.antennas()));
            tasks.await(
                    shutdown);
        } catch (RuntimeException ex) {
            shutdownFailure = ex;
        }

        try {
            tasks.close();
        } catch (RuntimeException ex) {
            if (shutdownFailure == null) {
                shutdownFailure = ex;
            } else if (shutdownFailure != ex) {
                shutdownFailure.addSuppressed(
                        ex);
            }
        }

        busy = false;
        activeOperation = null;
        switchingOperation = null;

        if (shutdownFailure == null) {
            state = State.INACTIVE;
            LOG.info(
                    "AntennaManager deactivated");
            return;
        }

        failure = shutdownFailure;
        state = State.FAILED;
        throw shutdownFailure;
    }

    private boolean requestInventory(
            boolean enabled) {
        if (state != State.ACTIVE) {
            return false;
        }

        inventoryEnabledSetting.request(
                Boolean.valueOf(
                        enabled));

        if (!inventoryEnabledSetting.changePending()) {
            return true;
        }

        if (tasks.execute(
                this::processInventorySetting)) {
            return true;
        }

        recordFailure(
                new IllegalStateException(
                        "AntennaManager control lane rejected inventory setting"));
        return false;
    }

    /** Runs on the manager serial lane and starts at most one transition task. */
    private void processInventorySetting() {
        if (state != State.ACTIVE
                || busy
                || !selfTestPassed
                || !inventoryEnabledSetting.changePending()) {
            return;
        }

        if (inventoryRequestedEnabled()) {
            startOperation(
                    "inventory enable",
                    AntennaTasks.enableInventory(
                            antennaSet.antennas(),
                            antennaSet.switching(),
                            this::inventoryRequestedEnabled),
                    this::inventoryEnableCompleted);
            return;
        }

        cancel(
                switchingOperation);
        switchingOperation = null;
        startOperation(
                "inventory disable",
                AntennaTasks.disableInventory(
                            antennaSet.antennas()),
                this::inventoryDisableCompleted);
    }

    private void selfTestCompleted(
            Throwable taskFailure) {
        if (state != State.ACTIVE) {
            busy = false;
            return;
        }

        if (taskFailure != null) {
            selfTestPassed = false;
            busy = false;
            recordFailure(
                    taskFailure);
            LOG.warn(
                    "AntennaManager self-test FAIL",
                    taskFailure);
            return;
        }

        selfTestPassed = antennaSet.allSelfTestsPassed();
        busy = false;

        LOG.info(
                "AntennaManager self-test {}",
                selfTestPassed
                        ? "PASS"
                        : "FAIL");

        if (selfTestPassed) {
            processInventorySetting();
        }
    }

    private void inventoryEnableCompleted(
            Throwable taskFailure) {
        busy = false;

        if (state != State.ACTIVE) {
            return;
        }

        if (taskFailure != null) {
            recordFailure(
                    taskFailure);
            LOG.warn(
                    "Antenna inventory enable failed",
                    taskFailure);

            if (!inventoryRequestedEnabled()) {
                startInventoryDisable();
            }
            return;
        }

        if (!inventoryRequestedEnabled()) {
            startInventoryDisable();
            return;
        }

        inventoryEnabledSetting.markApplied(
                Boolean.TRUE);
        startSwitching();
        LOG.info(
                "Antenna inventory enabled");

        if (inventoryEnabledSetting.changePending()) {
            processInventorySetting();
        }
    }

    private void inventoryDisableCompleted(
            Throwable taskFailure) {
        busy = false;

        if (state != State.ACTIVE) {
            return;
        }

        if (taskFailure != null) {
            recordFailure(
                    taskFailure);
            LOG.warn(
                    "Antenna inventory disable failed",
                    taskFailure);
            return;
        }

        inventoryEnabledSetting.markApplied(
                Boolean.FALSE);
        LOG.info(
                "Antenna inventory disabled");

        if (inventoryEnabledSetting.changePending()) {
            processInventorySetting();
        }
    }

    private void startInventoryDisable() {
        if (busy
                || state != State.ACTIVE) {
            return;
        }

        cancel(
                switchingOperation);
        switchingOperation = null;
        startOperation(
                "inventory disable",
                AntennaTasks.disableInventory(
                            antennaSet.antennas()),
                this::inventoryDisableCompleted);
    }

    private void startSwitching() {
        if (!antennaSet.switching().rotationNeeded()) {
            return;
        }

        cancel(
                switchingOperation);
        switchingOperation =
                tasks.runTask(
                        AntennaTasks.switchInventory(
                                antennaSet.switching(),
                                this::inventoryRequestedEnabled));

        switchingOperation.whenComplete(
                (ignored, taskFailure) -> {
                    if (taskFailure == null
                            || taskFailure instanceof CancellationException) {
                        return;
                    }
                    tasks.execute(
                            () -> recordFailure(
                                    taskFailure));
                });
    }

    /**
     * Starts one manager operation and always delivers completion back through
     * the same serial lane.
     */
    private void startOperation(
            String operation,
            CooperativeTask task,
            Consumer<Throwable> completed) {
        busy = true;

        final CompletableFuture<Void> running;
        try {
            running =
                    tasks.runTask(
                            task);
            activeOperation =
                    running;
        } catch (RuntimeException ex) {
            busy = false;
            recordFailure(
                    ex);
            LOG.warn(
                    "AntennaManager could not start {}",
                    operation,
                    ex);
            return;
        }

        running.whenComplete(
                (ignored, taskFailure) -> {
                    boolean accepted =
                            tasks.execute(
                                    () -> {
                                        if (activeOperation == running) {
                                            activeOperation = null;
                                        }
                                        completed.accept(
                                                taskFailure);
                                    });

                    if (!accepted) {
                        busy = false;
                        recordFailure(
                                new IllegalStateException(
                                        "AntennaManager control lane rejected "
                                                + operation
                                                + " completion",
                                        taskFailure));
                    }
                });
    }

    private boolean inventoryRequestedEnabled() {
        return Boolean.TRUE.equals(
                inventoryEnabledSetting.requestedValue());
    }

    private void recordFailure(
            Throwable cause) {
        if (cause == null
                || cause instanceof CancellationException) {
            return;
        }
        if (failure == null) {
            failure = cause;
        }
        if (tasks.failure() != null) {
            state = State.FAILED;
        }
    }

    private static void cancel(
            CompletableFuture<Void> operation) {
        if (operation != null
                && !operation.isDone()) {
            operation.cancel(
                    true);
        }
    }

}
