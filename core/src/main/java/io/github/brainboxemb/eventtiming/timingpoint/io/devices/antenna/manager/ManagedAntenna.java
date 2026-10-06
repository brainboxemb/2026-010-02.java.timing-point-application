package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.PowerDevice;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaOperation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task.AntennaTasks;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the device state of one configured physical antenna.
 *
 * <p>A startup self-test has one result: PASS or FAIL. Normal inventory
 * preparation is tracked separately through {@link AntennaOperation}.</p>
 *
 * <p>AntennaManager calls this object only from its one serial control lane.
 * The object therefore needs no internal locking and owns no scheduler.</p>
 */
final class ManagedAntenna implements AntennaTasks.AntennaTarget {
    private static final Logger LOG =
            LoggerFactory.getLogger(ManagedAntenna.class);

    private final AntennaInstallation installation;

    private volatile boolean selfTestPassed;
    private volatile AntennaOperation operation =
            AntennaOperation.INACTIVE;
    private volatile Throwable failure;
    private boolean externalPowerApplied;

    ManagedAntenna(
            AntennaInstallation installation) {
        if (installation == null) {
            throw new IllegalArgumentException(
                    "installation must not be null");
        }
        this.installation = installation;
    }

    AntennaId antennaId() {
        return installation.antennaId();
    }

    @Override
    public boolean inInventoryGroup() {
        return installation.inInventoryGroup();
    }

    Duration inventoryInterval() {
        return installation.inventoryInterval();
    }

    EventSource<TagObservation> tagObservedEvent() {
        return installation
                .antenna()
                .tagObservedEvent();
    }

    AntennaStatus status() {
        return new AntennaStatus(
                antennaId(),
                selfTestPassed,
                operation,
                failure);
    }

    @Override
    public Throwable failure() {
        return failure;
    }

    boolean selfTestPassed() {
        return selfTestPassed;
    }

    boolean availableForInventory() {
        return selfTestPassed
                && failure == null
                && operation != AntennaOperation.SHUTDOWN;
    }

    boolean inventoryRunning() {
        return operation == AntennaOperation.INVENTORY;
    }

    /**
     * Starts the startup self-test by applying external power when required.
     *
     * @return stabilization delay before self-test completion, or {@code null}
     *         when power preparation failed
     */
    @Override
    public Duration beginSelfTest() {
        if (operation == AntennaOperation.SHUTDOWN) {
            return null;
        }

        selfTestPassed = false;
        failure = null;

        try {
            return powerOn();
        } catch (RuntimeException ex) {
            selfTestFailed(
                    "self-test power-on",
                    ex);
            powerOffAfterFailure();
            return null;
        } catch (Error error) {
            selfTestFailed(
                    "self-test power-on",
                    error);
            powerOffAfterFailure();
            return null;
        }
    }

    /** Completes the startup self-test after any required stabilization delay. */
    @Override
    public void completeSelfTest() {
        if (operation == AntennaOperation.SHUTDOWN) {
            return;
        }

        try {
            installation.antenna().selfTest();
            selfTestPassed = true;
            LOG.info(
                    "Antenna {} self-test PASS",
                    antennaId());
        } catch (RuntimeException ex) {
            selfTestFailed(
                    "self-test",
                    ex);
        } catch (Error error) {
            selfTestFailed(
                    "self-test",
                    error);
        } finally {
            powerOffAfterSelfTest();
            if (operation != AntennaOperation.SHUTDOWN) {
                operation = AntennaOperation.INACTIVE;
            }
        }
    }

    /** Records a manager/control failure for this antenna self-test. */
    void selfTestControlFailed(
            Throwable cause) {
        selfTestFailed(
                "self-test control",
                cause);
        powerOffAfterFailure();
        if (operation != AntennaOperation.SHUTDOWN) {
            operation = AntennaOperation.INACTIVE;
        }
    }

    /**
     * Starts normal inventory preparation by applying external power.
     *
     * @return stabilization delay before initialize, or {@code null} when this
     *         antenna cannot currently be prepared
     */
    @Override
    public Duration beginPrepareForInventory() {
        if (!availableForInventory()
                || operation == AntennaOperation.INVENTORY
                || operation == AntennaOperation.READY
                || operation == AntennaOperation.PREPARING) {
            return null;
        }

        operation = AntennaOperation.PREPARING;

        try {
            return powerOn();
        } catch (RuntimeException ex) {
            operationFailed(
                    "inventory power-on",
                    ex);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return null;
        } catch (Error error) {
            operationFailed(
                    "inventory power-on",
                    error);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return null;
        }
    }

    /** Initializes this antenna after stabilization. */
    @Override
    public boolean completePrepareForInventory() {
        if (!availableForInventory()
                || operation != AntennaOperation.PREPARING) {
            return false;
        }

        try {
            installation.antenna().initialize();
            operation = AntennaOperation.READY;
            LOG.debug(
                    "Antenna {} prepared for inventory",
                    antennaId());
            return true;
        } catch (RuntimeException ex) {
            operationFailed(
                    "initialize",
                    ex);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return false;
        } catch (Error error) {
            operationFailed(
                    "initialize",
                    error);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return false;
        }
    }

    /** Starts provider inventory when this antenna is prepared. */
    @Override
    public boolean startInventory() {
        if (operation == AntennaOperation.INVENTORY) {
            return true;
        }
        if (!availableForInventory()
                || operation != AntennaOperation.READY) {
            return false;
        }

        try {
            installation.antenna().startInventory();
            operation = AntennaOperation.INVENTORY;
            LOG.info(
                    "Antenna {} inventory started",
                    antennaId());
            return true;
        } catch (RuntimeException ex) {
            operationFailed(
                    "start inventory",
                    ex);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return false;
        } catch (Error error) {
            operationFailed(
                    "start inventory",
                    error);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return false;
        }
    }

    /**
     * Stops provider inventory but keeps an antenna prepared.
     *
     * <p>The boolean result matters for multiplex safety. A switch controller
     * must not start another group member when stopping the current member
     * failed, because the old reader may still be inventorying.</p>
     */
    boolean stopInventory() {
        if (operation != AntennaOperation.INVENTORY) {
            return true;
        }

        try {
            installation.antenna().stopInventory();
            operation = AntennaOperation.READY;
            LOG.info(
                    "Antenna {} inventory stopped",
                    antennaId());
            return true;
        } catch (RuntimeException ex) {
            operationFailed(
                    "stop inventory",
                    ex);
            return false;
        } catch (Error error) {
            operationFailed(
                    "stop inventory",
                    error);
            return false;
        }
    }

    /**
     * Stops normal inventory use and removes external power when configured.
     *
     * @return {@code true} when the requested disabled state was applied
     */
    @Override
    public boolean disableInventory() {
        boolean stopped =
                stopInventory();
        boolean poweredOff = true;

        try {
            powerOff();
            if (stopped
                    || installation.powerDevice() != null) {
                operation = AntennaOperation.INACTIVE;
            }
        } catch (RuntimeException ex) {
            poweredOff = false;
            operationFailed(
                    "inventory power-off",
                    ex);
        }

        return stopped && poweredOff;
    }

    /** Releases all provider/device resources owned by this configured antenna. */
    @Override
    public void shutdown() {
        RuntimeException firstFailure = null;

        if (!stopInventory()) {
            Throwable stopFailure =
                    failure;
            if (stopFailure instanceof RuntimeException) {
                firstFailure =
                        (RuntimeException) stopFailure;
            }
        }

        try {
            powerOff();
        } catch (RuntimeException ex) {
            firstFailure =
                    appendFailure(
                            firstFailure,
                            ex);
        }

        try {
            installation.antenna().shutdown();
        } catch (RuntimeException ex) {
            operationFailed(
                    "shutdown",
                    ex);
            firstFailure =
                    appendFailure(
                            firstFailure,
                            ex);
        }

        operation = AntennaOperation.SHUTDOWN;

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /** Applies external power and returns the required stabilization delay. */
    private Duration powerOn() {
        PowerDevice power =
                installation.powerDevice();

        if (power == null
                || externalPowerApplied) {
            return Duration.ZERO;
        }

        power.powerOn();
        externalPowerApplied = true;
        LOG.debug(
                "Antenna {} external power enabled",
                antennaId());
        return installation.powerStabilization();
    }

    private void powerOff() {
        PowerDevice power =
                installation.powerDevice();

        if (power == null
                || !externalPowerApplied) {
            return;
        }

        try {
            power.powerOff();
            LOG.debug(
                    "Antenna {} external power disabled",
                    antennaId());
        } finally {
            externalPowerApplied = false;
        }
    }

    private void powerOffAfterSelfTest() {
        if (installation.powerDevice() == null) {
            return;
        }

        try {
            powerOff();
        } catch (RuntimeException ex) {
            selfTestFailed(
                    "self-test power-off",
                    ex);
        }
    }

    private void powerOffAfterFailure() {
        try {
            powerOff();
        } catch (RuntimeException powerFailure) {
            if (failure != null) {
                failure.addSuppressed(
                        powerFailure);
            }
        }
    }

    private void selfTestFailed(
            String action,
            Throwable cause) {
        selfTestPassed = false;
        recordFailure(
                action,
                cause);
        LOG.warn(
                "Antenna {} self-test FAIL during {}",
                antennaId(),
                action,
                cause);
    }

    private void operationFailed(
            String action,
            Throwable cause) {
        recordFailure(
                action,
                cause);
        LOG.warn(
                "Antenna {} failed during {}",
                antennaId(),
                action,
                cause);
    }

    private void recordFailure(
            String action,
            Throwable cause) {
        if (failure == null) {
            failure = cause;
        } else if (failure != cause) {
            failure.addSuppressed(
                    cause);
        }
    }

    private static RuntimeException appendFailure(
            RuntimeException current,
            RuntimeException later) {
        if (current == null) {
            return later;
        }
        if (current != later) {
            current.addSuppressed(
                    later);
        }
        return current;
    }
}
