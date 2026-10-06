package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInfo;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaOperation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task.AntennaTasks;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.PowerDevice;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runtime state and direct device operations for one configured antenna. */
final class ManagedAntenna implements AntennaTasks.AntennaTarget {
    private static final Logger LOG = LoggerFactory.getLogger(ManagedAntenna.class);

    private final AntennaInstallation installation;

    private boolean selfTestPassed;
    private AntennaOperation operation = AntennaOperation.INACTIVE;
    private Throwable failure;
    private boolean externalPowerApplied;

    ManagedAntenna(AntennaInstallation installation) {
        if (installation == null) {
            throw new IllegalArgumentException("installation must not be null");
        }
        this.installation = installation;
    }

    AntennaId antennaId() {
        return installation.antennaId();
    }

    boolean inInventoryGroup() {
        return installation.inInventoryGroup();
    }

    Duration inventoryInterval() {
        return installation.inventoryInterval();
    }

    EventSource<TagObservation> tagObservedEvent() {
        return installation.antenna().tagObservedEvent();
    }

    AntennaStatus status() {
        return new AntennaStatus(antennaId(), selfTestPassed, operation, failure);
    }

    boolean selfTestPassed() {
        return selfTestPassed;
    }

    @Override
    public void beginSelfTest() {
        selfTestPassed = false;
        failure = null;
        operation = AntennaOperation.PREPARING;
    }

    @Override
    public boolean availableForInventory() {
        return selfTestPassed && failure == null;
    }

    @Override
    public boolean inventoryRunning() {
        return operation == AntennaOperation.INVENTORY;
    }

    @Override
    public void powerOn() {
        PowerDevice power = installation.powerDevice();
        if (power == null || externalPowerApplied) {
            return;
        }

        try {
            power.powerOn();
            externalPowerApplied = true;
            LOG.debug("Antenna {} external power enabled", antennaId());
        } catch (RuntimeException ex) {
            recordFailure(ex);
            throw ex;
        }
    }

    @Override
    public void powerOff() {
        PowerDevice power = installation.powerDevice();

        try {
            if (power != null && externalPowerApplied) {
                power.powerOff();
                LOG.debug("Antenna {} external power disabled", antennaId());
            }
        } catch (RuntimeException ex) {
            recordFailure(ex);
            throw ex;
        } finally {
            externalPowerApplied = false;
            if (operation != AntennaOperation.INVENTORY) {
                operation = AntennaOperation.INACTIVE;
            }
        }
    }

    @Override
    public Duration powerStabilization() {
        return installation.powerStabilization();
    }

    @Override
    public AntennaInfo selfTest() {
        try {
            AntennaInfo info = installation.antenna().selfTest();
            selfTestPassed = true;
            LOG.info("Antenna {} self-test PASS", antennaId());
            return info;
        } catch (RuntimeException ex) {
            selfTestPassed = false;
            recordFailure(ex);
            LOG.warn("Antenna {} self-test FAIL", antennaId(), ex);
            throw ex;
        }
    }

    @Override
    public void beginInventoryPreparation() {
        if (!availableForInventory()
                || operation == AntennaOperation.INVENTORY
                || operation == AntennaOperation.READY
                || operation == AntennaOperation.PREPARING) {
            throw new IllegalStateException("Antenna " + antennaId() + " cannot prepare inventory from " + operation);
        }
        operation = AntennaOperation.PREPARING;
    }

    @Override
    public void initialize() {
        if (!availableForInventory() || operation != AntennaOperation.PREPARING) {
            throw new IllegalStateException("Antenna " + antennaId() + " cannot initialize from " + operation);
        }

        try {
            installation.antenna().initialize();
            operation = AntennaOperation.READY;
            LOG.debug("Antenna {} prepared for inventory", antennaId());
        } catch (RuntimeException ex) {
            operation = AntennaOperation.INACTIVE;
            recordFailure(ex);
            throw ex;
        }
    }

    @Override
    public void startInventory() {
        if (operation == AntennaOperation.INVENTORY) {
            return;
        }
        if (!availableForInventory() || operation != AntennaOperation.READY) {
            throw new IllegalStateException("Antenna " + antennaId() + " cannot start inventory from " + operation);
        }

        try {
            installation.antenna().startInventory();
            operation = AntennaOperation.INVENTORY;
            LOG.info("Antenna {} inventory started", antennaId());
        } catch (RuntimeException ex) {
            recordFailure(ex);
            throw ex;
        }
    }

    @Override
    public void stopInventory() {
        if (operation != AntennaOperation.INVENTORY) {
            return;
        }

        try {
            installation.antenna().stopInventory();
            operation = AntennaOperation.READY;
            LOG.info("Antenna {} inventory stopped", antennaId());
        } catch (RuntimeException ex) {
            recordFailure(ex);
            throw ex;
        }
    }

    @Override
    public void shutdownProvider() {
        try {
            installation.antenna().shutdown();
        } catch (RuntimeException ex) {
            recordFailure(ex);
            throw ex;
        } finally {
            operation = AntennaOperation.INACTIVE;
            selfTestPassed = false;
        }
    }

    private void recordFailure(Throwable cause) {
        if (failure == null) {
            failure = cause;
        } else if (failure != cause) {
            failure.addSuppressed(cause);
        }
    }
}
