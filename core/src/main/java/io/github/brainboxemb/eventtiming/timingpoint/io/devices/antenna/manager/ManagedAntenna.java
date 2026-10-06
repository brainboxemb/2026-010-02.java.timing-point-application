package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaOperation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task.AntennaTasks;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.PowerDevice;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime state and direct device operations for one configured antenna.
 *
 * <p>Multi-step sequencing belongs to cooperative tasks. Each public task-port
 * method here performs at most one physical device action and updates the
 * corresponding runtime state.</p>
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
        return installation.antenna()
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

    @Override
    public Duration powerOnForSelfTest() {
        requireNotShutdown(
                "self-test power-on");
        selfTestPassed = false;
        failure = null;

        try {
            return powerOn();
        } catch (RuntimeException ex) {
            selfTestFailed(
                    "power-on",
                    ex);
            throw ex;
        }
    }

    @Override
    public void selfTest() {
        requireNotShutdown(
                "self-test");

        try {
            installation.antenna()
                    .selfTest();
            selfTestPassed = true;
            LOG.info(
                    "Antenna {} self-test PASS",
                    antennaId());
        } catch (RuntimeException ex) {
            selfTestFailed(
                    "self-test",
                    ex);
            throw ex;
        }
    }

    @Override
    public void powerOffAfterSelfTest() {
        try {
            powerOff();
        } catch (RuntimeException ex) {
            selfTestFailed(
                    "power-off",
                    ex);
            throw ex;
        } finally {
            if (operation != AntennaOperation.SHUTDOWN) {
                operation = AntennaOperation.INACTIVE;
            }
        }
    }

    @Override
    public Duration powerOnForInventory() {
        if (!availableForInventory()
                || operation == AntennaOperation.INVENTORY
                || operation == AntennaOperation.READY
                || operation == AntennaOperation.PREPARING) {
            throw new IllegalStateException(
                    "Antenna "
                            + antennaId()
                            + " cannot begin inventory preparation from "
                            + operation);
        }

        operation = AntennaOperation.PREPARING;

        try {
            return powerOn();
        } catch (RuntimeException ex) {
            inventoryFailed(
                    "power-on",
                    ex);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            throw ex;
        }
    }

    @Override
    public void initialize() {
        if (!availableForInventory()
                || operation != AntennaOperation.PREPARING) {
            throw new IllegalStateException(
                    "Antenna "
                            + antennaId()
                            + " cannot initialize from "
                            + operation);
        }

        try {
            installation.antenna()
                    .initialize();
            operation = AntennaOperation.READY;
            LOG.debug(
                    "Antenna {} prepared for inventory",
                    antennaId());
        } catch (RuntimeException ex) {
            inventoryFailed(
                    "initialize",
                    ex);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
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
                            + antennaId()
                            + " cannot start inventory from "
                            + operation);
        }

        try {
            installation.antenna()
                    .startInventory();
            operation = AntennaOperation.INVENTORY;
            LOG.info(
                    "Antenna {} inventory started",
                    antennaId());
        } catch (RuntimeException ex) {
            inventoryFailed(
                    "start inventory",
                    ex);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            throw ex;
        }
    }

    @Override
    public void stopInventory() {
        if (operation != AntennaOperation.INVENTORY) {
            return;
        }

        try {
            installation.antenna()
                    .stopInventory();
            operation = AntennaOperation.READY;
            LOG.info(
                    "Antenna {} inventory stopped",
                    antennaId());
        } catch (RuntimeException ex) {
            inventoryFailed(
                    "stop inventory",
                    ex);
            throw ex;
        }
    }

    @Override
    public void powerOffAfterInventory() {
        boolean externalPower =
                installation.powerDevice() != null;

        try {
            powerOff();
            if (operation != AntennaOperation.INVENTORY
                    || externalPower) {
                operation = AntennaOperation.INACTIVE;
            }
        } catch (RuntimeException ex) {
            inventoryFailed(
                    "power-off",
                    ex);
            throw ex;
        }
    }

    @Override
    public void shutdownProvider() {
        try {
            installation.antenna()
                    .shutdown();
        } catch (RuntimeException ex) {
            inventoryFailed(
                    "shutdown",
                    ex);
            throw ex;
        } finally {
            operation = AntennaOperation.SHUTDOWN;
        }
    }

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

    private void powerOffAfterFailure() {
        try {
            powerOff();
        } catch (RuntimeException powerFailure) {
            if (failure != null
                    && failure != powerFailure) {
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
                cause);
        LOG.warn(
                "Antenna {} self-test FAIL during {}",
                antennaId(),
                action,
                cause);
    }

    private void inventoryFailed(
            String action,
            Throwable cause) {
        recordFailure(
                cause);
        LOG.warn(
                "Antenna {} failed during {}",
                antennaId(),
                action,
                cause);
    }

    private void recordFailure(
            Throwable cause) {
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
                            + antennaId()
                            + " cannot perform "
                            + action
                            + " after shutdown");
        }
    }
}
