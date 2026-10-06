package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.infra.setting.Setting;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task.AntennaTasks;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reconciles requested inventory state with the managed antenna set.
 *
 * <p>This object owns only the inventory setting and its operation tasks. It
 * does not own the manager lifecycle, startup self-test or physical worker.</p>
 */
final class InventoryController {
    private static final Logger LOG =
            LoggerFactory.getLogger(InventoryController.class);

    private final ManagedAntennaSet antennaSet;
    private final ScheduledTaskRunner tasks;
    private final Consumer<Throwable> failureSink;

    private final Setting<Boolean> inventoryEnabledSetting =
            new Setting<Boolean>(
                    Boolean.FALSE);

    private boolean ready;
    private volatile boolean busy;
    private CompletableFuture<Void> activeOperation;
    private CompletableFuture<Void> switchingOperation;

    InventoryController(
            ManagedAntennaSet antennaSet,
            ScheduledTaskRunner tasks,
            Consumer<Throwable> failureSink) {
        if (antennaSet == null) {
            throw new IllegalArgumentException(
                    "antennaSet must not be null");
        }
        if (tasks == null) {
            throw new IllegalArgumentException(
                    "tasks must not be null");
        }
        if (failureSink == null) {
            throw new IllegalArgumentException(
                    "failureSink must not be null");
        }

        this.antennaSet = antennaSet;
        this.tasks = tasks;
        this.failureSink = failureSink;
    }

    boolean isBusy() {
        return busy;
    }

    boolean requestEnabled(
            boolean enabled) {
        inventoryEnabledSetting.request(
                Boolean.valueOf(
                        enabled));

        if (!inventoryEnabledSetting.changePending()) {
            return true;
        }

        if (tasks.execute(
                this::processSetting)) {
            return true;
        }

        failureSink.accept(
                new IllegalStateException(
                        "AntennaManager control lane rejected inventory setting"));
        return false;
    }

    /** Called on the manager lane after startup self-test has passed. */
    void ready() {
        ready = true;
        processSetting();
    }

    /** Stops inventory reconciliation; shutdown handles final device cleanup. */
    void stop() {
        ready = false;
        inventoryEnabledSetting.request(
                Boolean.FALSE);
        cancel(
                switchingOperation);
        cancel(
                activeOperation);
        switchingOperation = null;
        activeOperation = null;
        busy = false;
    }

    private void processSetting() {
        if (!ready
                || busy
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
                    this::enableCompleted);
            return;
        }

        stopSwitching();
        startOperation(
                "inventory disable",
                AntennaTasks.disableInventory(
                        antennaSet.antennas()),
                this::disableCompleted);
    }

    private void enableCompleted(
            Throwable taskFailure) {
        busy = false;

        if (taskFailure != null) {
            reportFailure(
                    "Antenna inventory enable failed",
                    taskFailure);

            if (!inventoryRequestedEnabled()) {
                startDisable();
            }
            return;
        }

        if (!inventoryRequestedEnabled()) {
            startDisable();
            return;
        }

        inventoryEnabledSetting.markApplied(
                Boolean.TRUE);
        startSwitching();
        LOG.info(
                "Antenna inventory enabled");

        if (inventoryEnabledSetting.changePending()) {
            processSetting();
        }
    }

    private void disableCompleted(
            Throwable taskFailure) {
        busy = false;

        if (taskFailure != null) {
            reportFailure(
                    "Antenna inventory disable failed",
                    taskFailure);
            return;
        }

        inventoryEnabledSetting.markApplied(
                Boolean.FALSE);
        LOG.info(
                "Antenna inventory disabled");

        if (inventoryEnabledSetting.changePending()) {
            processSetting();
        }
    }

    private void startDisable() {
        if (busy
                || !ready) {
            return;
        }

        stopSwitching();
        startOperation(
                "inventory disable",
                AntennaTasks.disableInventory(
                        antennaSet.antennas()),
                this::disableCompleted);
    }

    private void startSwitching() {
        if (!antennaSet.switching()
                .rotationNeeded()) {
            return;
        }

        stopSwitching();
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
                            () -> failureSink.accept(
                                    taskFailure));
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
                    tasks.runTask(
                            task);
            activeOperation =
                    running;
        } catch (RuntimeException ex) {
            busy = false;
            reportFailure(
                    "AntennaManager could not start " + operation,
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
                        failureSink.accept(
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

    private void reportFailure(
            String message,
            Throwable failure) {
        LOG.warn(
                message,
                failure);
        failureSink.accept(
                failure);
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
