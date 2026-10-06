package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInfo;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaSelfTestResult;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaOperation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task.AntennaTasks;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.PowerDevice;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Active runtime component for one antenna.
 *
 * <p>The component owns antenna identity, provider, optional external power,
 * power-stabilization configuration, runtime state and one reusable self-test
 * task. Inventory-group membership remains AntennaManager configuration.</p>
 */
public final class AntennaDevice implements AntennaTasks.AntennaTarget {
    private static final Logger LOG =
            LoggerFactory.getLogger(AntennaDevice.class);

    private final AntennaId antennaId;
    private final Antenna antenna;
    private final PowerDevice powerDevice;
    private final Duration powerStabilization;
    private final AntennaTasks.ReusableTask selfTestTask;
    private final Event<AntennaSelfTestResult> selfTestCompleted =
            new Event<AntennaSelfTestResult>();

    private volatile boolean selfTestPassed;
    private volatile AntennaOperation operation =
            AntennaOperation.INACTIVE;
    private volatile Throwable failure;
    private volatile AntennaInfo selfTestInfo;
    private CompletableFuture<Void> selfTestOperation;
    private boolean externalPowerApplied;

    public AntennaDevice(
            AntennaId antennaId,
            Antenna antenna,
            PowerDevice powerDevice,
            Duration powerStabilization) {
        if (antennaId == null) {
            throw new IllegalArgumentException(
                    "antennaId must not be null");
        }
        if (antenna == null) {
            throw new IllegalArgumentException(
                    "antenna must not be null");
        }
        if (powerStabilization == null
                || powerStabilization.isNegative()) {
            throw new IllegalArgumentException(
                    "powerStabilization must not be negative");
        }

        this.antennaId = antennaId;
        this.antenna = antenna;
        this.powerDevice = powerDevice;
        this.powerStabilization = powerStabilization;
        selfTestTask =
                AntennaTasks.selfTestTask(
                        this);
    }

    public static AntennaDevice direct(
            AntennaId antennaId,
            Antenna antenna) {
        return new AntennaDevice(
                antennaId,
                antenna,
                null,
                Duration.ZERO);
    }

    public static AntennaDevice powered(
            AntennaId antennaId,
            Antenna antenna,
            PowerDevice powerDevice,
            Duration powerStabilization) {
        if (powerDevice == null) {
            throw new IllegalArgumentException(
                    "powerDevice must not be null");
        }
        return new AntennaDevice(
                antennaId,
                antenna,
                powerDevice,
                powerStabilization);
    }

    AntennaId antennaId() {
        return antennaId;
    }

    EventSource<TagObservation> tagObservedEvent() {
        return antenna.tagObservedEvent();
    }

    EventSource<AntennaSelfTestResult> selfTestCompletedEvent() {
        return selfTestCompleted;
    }

    AntennaStatus status() {
        return new AntennaStatus(
                antennaId,
                selfTestPassed,
                operation,
                failure);
    }

    boolean selfTestPassed() {
        return selfTestPassed;
    }

    boolean startSelfTest(
            ScheduledTaskRunner taskRunner) {
        if (taskRunner == null) {
            throw new IllegalArgumentException(
                    "taskRunner must not be null");
        }
        if (selfTestOperation != null
                && !selfTestOperation.isDone()) {
            return false;
        }

        selfTestPassed = false;
        selfTestInfo = null;
        failure = null;
        operation = AntennaOperation.PREPARING;

        selfTestTask.reset();
        selfTestOperation =
                taskRunner.runTask(
                        selfTestTask);

        selfTestOperation.whenComplete(
                (ignored, taskFailure) ->
                        finishSelfTest(
                                taskFailure));
        return true;
    }

    @Override
    public boolean availableForInventory() {
        return selfTestPassed
                && failure == null
                && operation != AntennaOperation.SHUTDOWN;
    }

    @Override
    public boolean inventoryRunning() {
        return operation == AntennaOperation.INVENTORY;
    }

    @Override
    public void powerOn() {
        requireNotShutdown(
                "power-on");

        if (powerDevice == null
                || externalPowerApplied) {
            return;
        }

        try {
            powerDevice.powerOn();
            externalPowerApplied = true;
            LOG.debug(
                    "Antenna {} external power enabled",
                    antennaId);
        } catch (RuntimeException ex) {
            recordFailure(
                    ex);
            throw ex;
        }
    }

    @Override
    public void powerOff() {
        try {
            if (powerDevice != null
                    && externalPowerApplied) {
                powerDevice.powerOff();
                LOG.debug(
                        "Antenna {} external power disabled",
                        antennaId);
            }
        } catch (RuntimeException ex) {
            recordFailure(
                    ex);
            throw ex;
        } finally {
            externalPowerApplied = false;
            if (operation != AntennaOperation.INVENTORY
                    && operation != AntennaOperation.SHUTDOWN) {
                operation = AntennaOperation.INACTIVE;
            }
        }
    }

    @Override
    public Duration powerStabilization() {
        return powerStabilization;
    }

    @Override
    public AntennaInfo selfTest() {
        requireNotShutdown(
                "self-test");

        try {
            AntennaInfo info =
                    antenna.selfTest();
            selfTestInfo = info;
            selfTestPassed = true;
            LOG.info(
                    "Antenna {} self-test PASS",
                    antennaId);
            return info;
        } catch (RuntimeException ex) {
            selfTestPassed = false;
            recordFailure(
                    ex);
            LOG.warn(
                    "Antenna {} self-test FAIL",
                    antennaId,
                    ex);
            throw ex;
        }
    }

    @Override
    public void beginInventoryPreparation() {
        if (!availableForInventory()
                || operation == AntennaOperation.INVENTORY
                || operation == AntennaOperation.READY
                || operation == AntennaOperation.PREPARING) {
            throw new IllegalStateException(
                    "Antenna "
                            + antennaId
                            + " cannot begin inventory preparation from "
                            + operation);
        }

        operation = AntennaOperation.PREPARING;
    }

    @Override
    public void initialize() {
        if (!availableForInventory()
                || operation != AntennaOperation.PREPARING) {
            throw new IllegalStateException(
                    "Antenna "
                            + antennaId
                            + " cannot initialize from "
                            + operation);
        }

        try {
            antenna.initialize();
            operation = AntennaOperation.READY;
            LOG.debug(
                    "Antenna {} prepared for inventory",
                    antennaId);
        } catch (RuntimeException ex) {
            operation = AntennaOperation.INACTIVE;
            recordFailure(
                    ex);
            throw ex;
        }
    }

    @Override
    public void startInventory() {
        if (operation == AntennaOperation.INVENTORY) {
            return;
        }
        if (!availableForInventory()
                || operation != AntennaOperation.READY) {
            throw new IllegalStateException(
                    "Antenna "
                            + antennaId
                            + " cannot start inventory from "
                            + operation);
        }

        try {
            antenna.startInventory();
            operation = AntennaOperation.INVENTORY;
            LOG.info(
                    "Antenna {} inventory started",
                    antennaId);
        } catch (RuntimeException ex) {
            recordFailure(
                    ex);
            throw ex;
        }
    }

    @Override
    public void stopInventory() {
        if (operation != AntennaOperation.INVENTORY) {
            return;
        }

        try {
            antenna.stopInventory();
            operation = AntennaOperation.READY;
            LOG.info(
                    "Antenna {} inventory stopped",
                    antennaId);
        } catch (RuntimeException ex) {
            recordFailure(
                    ex);
            throw ex;
        }
    }

    @Override
    public void shutdownProvider() {
        try {
            antenna.shutdown();
        } catch (RuntimeException ex) {
            recordFailure(
                    ex);
            throw ex;
        } finally {
            operation = AntennaOperation.SHUTDOWN;
        }
    }

    private void finishSelfTest(
            Throwable taskFailure) {
        selfTestOperation = null;
        operation = AntennaOperation.INACTIVE;

        AntennaSelfTestResult result;
        if (taskFailure == null
                && selfTestPassed
                && selfTestInfo != null) {
            result =
                    AntennaSelfTestResult.passed(
                            antennaId,
                            selfTestInfo);
        } else {
            Throwable effectiveFailure =
                    taskFailure != null
                            ? taskFailure
                            : failure;
            if (effectiveFailure == null) {
                effectiveFailure =
                        new IllegalStateException(
                                "antenna self-test did not produce a result");
            }
            recordFailure(
                    effectiveFailure);
            selfTestPassed = false;
            result =
                    AntennaSelfTestResult.failed(
                            antennaId,
                            effectiveFailure);
        }

        selfTestCompleted.emit(
                result);
    }

    private void recordFailure(
            Throwable cause) {
        if (cause == null) {
            return;
        }
        if (failure == null) {
            failure = cause;
        } else if (failure != cause) {
            failure.addSuppressed(
                    cause);
        }
    }

    private void requireNotShutdown(
            String action) {
        if (operation == AntennaOperation.SHUTDOWN) {
            throw new IllegalStateException(
                    "Antenna "
                            + antennaId
                            + " cannot perform "
                            + action
                            + " after shutdown");
        }
    }
}
