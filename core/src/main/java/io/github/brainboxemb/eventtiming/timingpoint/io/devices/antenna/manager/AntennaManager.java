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
 * Public lifecycle, inventory-intent and status boundary for one antenna set.
 *
 * <p>The manager owns one requested/applied inventory setting and one reusable
 * task set. Multi-step device sequencing lives in the task state machines.</p>
 */
public final class AntennaManager {
    private static final Logger LOG =
            LoggerFactory.getLogger(AntennaManager.class);

    private final ManagedAntennaSet antennaSet;
    private final ScheduledTaskRunner taskRunner;
    private final AntennaTasks antennaTasks;
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
        taskRunner =
                new ScheduledTaskRunner(
                        controlLane,
                        controlTimeout);
        antennaTasks =
                new AntennaTasks(
                        antennaSet.antennas(),
                        antennaSet.inventoryGroup(),
                        antennaSet.hasInventoryGroup()
                                ? antennaSet.inventoryInterval()
                                : null,
                        this::inventoryRequestedEnabled);
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

        taskRunner.start();
        state = State.ACTIVE;

        LOG.info(
                "AntennaManager activated with {} configured antenna(s)",
                antennaSet.size());

        startOperation(
                "self-test",
                antennaTasks.selfTest(),
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
        stopSwitching();
        cancel(
                activeOperation);

        RuntimeException shutdownFailure = null;
        try {
            if (taskRunner.isNew()) {
                taskRunner.start();
            }

            CompletableFuture<Void> shutdown =
                    taskRunner.runTask(
                            antennaTasks.shutdown());
            taskRunner.await(
                    shutdown);
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

        busy = false;
        activeOperation = null;

        if (shutdownFailure == null) {
            state = State.INACTIVE;
            LOG.info(
                    "AntennaManager deactivated");
        } else {
            failure = shutdownFailure;
            state = State.FAILED;
            throw shutdownFailure;
        }
    }

    private boolean requestInventory(
            boolean enabled) {
        if (state != State.ACTIVE) {
            return false;
        }

        inventoryEnabledSetting.request(
                Boolean.valueOf(
                        enabled));

        boolean accepted = true;
        if (inventoryEnabledSetting.changePending()) {
            accepted =
                    taskRunner.execute(
                            this::processInventorySetting);

            if (!accepted) {
                recordFailure(
                        new IllegalStateException(
                                "AntennaManager control lane rejected inventory setting"));
            }
        }

        return accepted;
    }

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
                    antennaTasks.enableInventory(),
                    this::inventoryEnableCompleted);
        } else {
            stopSwitching();
            startOperation(
                    "inventory disable",
                    antennaTasks.disableInventory(),
                    this::inventoryDisableCompleted);
        }
    }

    private void selfTestCompleted(
            Throwable taskFailure) {
        if (state != State.ACTIVE) {
            return;
        }

        if (taskFailure == null) {
            selfTestPassed =
                    antennaSet.allSelfTestsPassed();

            LOG.info(
                    "AntennaManager self-test {}",
                    selfTestPassed
                            ? "PASS"
                            : "FAIL");

            if (selfTestPassed) {
                processInventorySetting();
            }
        } else {
            selfTestPassed = false;
            recordFailure(
                    taskFailure);
            LOG.warn(
                    "AntennaManager self-test FAIL",
                    taskFailure);
        }
    }

    private void inventoryEnableCompleted(
            Throwable taskFailure) {
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
        } else if (!inventoryRequestedEnabled()) {
            startInventoryDisable();
        } else {
            inventoryEnabledSetting.markApplied(
                    Boolean.TRUE);
            startSwitching();
            LOG.info(
                    "Antenna inventory enabled");

            if (inventoryEnabledSetting.changePending()) {
                processInventorySetting();
            }
        }
    }

    private void inventoryDisableCompleted(
            Throwable taskFailure) {
        if (state != State.ACTIVE) {
            return;
        }

        if (taskFailure != null) {
            recordFailure(
                    taskFailure);
            LOG.warn(
                    "Antenna inventory disable failed",
                    taskFailure);
        } else {
            inventoryEnabledSetting.markApplied(
                    Boolean.FALSE);
            LOG.info(
                    "Antenna inventory disabled");

            if (inventoryEnabledSetting.changePending()) {
                processInventorySetting();
            }
        }
    }

    private void startInventoryDisable() {
        if (busy
                || state != State.ACTIVE) {
            return;
        }

        stopSwitching();
        startOperation(
                "inventory disable",
                antennaTasks.disableInventory(),
                this::inventoryDisableCompleted);
    }

    private void startSwitching() {
        if (!antennaSet.hasInventoryGroup()) {
            return;
        }

        stopSwitching();
        switchingOperation =
                taskRunner.runTask(
                        antennaTasks.switchInventory());

        switchingOperation.whenComplete(
                (ignored, taskFailure) -> {
                    if (taskFailure != null
                            && !(taskFailure instanceof CancellationException)) {
                        taskRunner.execute(
                                () -> recordFailure(
                                        taskFailure));
                    }
                });
    }

    private void stopSwitching() {
        cancel(
                switchingOperation);
        switchingOperation = null;
    }

    private void startOperation(
            String operation,
            CooperativeTask task,
            Consumer<Throwable> completed) {
        busy = true;

        final CompletableFuture<Void> running;
        try {
            running =
                    taskRunner.runTask(
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
                            taskRunner.execute(
                                    () -> {
                                        if (activeOperation == running) {
                                            activeOperation = null;
                                        }
                                        busy = false;
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
        if (taskRunner.failure() != null) {
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
