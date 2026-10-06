package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInfo;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaOperation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.PowerDevice;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * Runtime state for one antenna owned by {@link AntennaManager}.
 *
 * <p>This class does not schedule work. Tasks call its direct device operations;
 * it records the resulting self-test, operation and failure state used by the
 * manager status API.</p>
 */
final class ManagedAntenna {
    private static final Logger LOG = LoggerFactory.getLogger(ManagedAntenna.class);

    private final AntennaId antennaId;
    private final Antenna antenna;
    private final PowerDevice powerDevice;
    private final Duration powerStabilization;

    private boolean selfTestPassed;
    private AntennaOperation operation = AntennaOperation.INACTIVE;
    private Throwable failure;
    private boolean externalPowerApplied;

    ManagedAntenna(
            AntennaId antennaId,
            Antenna antenna,
            PowerDevice powerDevice,
            Duration powerStabilization) {
        this.antennaId = antennaId;
        this.antenna = antenna;
        this.powerDevice = powerDevice;
        this.powerStabilization = powerStabilization;
    }

    AntennaId antennaId() {
        return antennaId;
    }

    EventSource<TagObservation> tagObservedEvent() {
        return antenna.tagObservedEvent();
    }

    AntennaStatus status() {
        return new AntennaStatus(antennaId, selfTestPassed, operation, failure);
    }

    boolean selfTestPassed() {
        return selfTestPassed;
    }

    /**
     * Starts a new self-test result window before the task touches hardware.
     */
    public void beginSelfTest() {
        selfTestPassed = false;
        failure = null;
        operation = AntennaOperation.PREPARING;
    }

    public boolean availableForInventory() {
        return selfTestPassed && failure == null;
    }

    public boolean inventoryRunning() {
        return operation == AntennaOperation.INVENTORY;
    }

    public void powerOn() {
        if (powerDevice == null || externalPowerApplied) {
            return;
        }

        try {
            powerDevice.powerOn();
            externalPowerApplied = true;
            LOG.debug("Antenna {} external power enabled", antennaId);
        } catch (RuntimeException ex) {
            recordFailure(ex);
            throw ex;
        }
    }

    public void powerOff() {
        try {
            if (powerDevice != null && externalPowerApplied) {
                powerDevice.powerOff();
                LOG.debug("Antenna {} external power disabled", antennaId);
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

    public Duration powerStabilization() {
        return powerStabilization;
    }

    public AntennaInfo selfTest() {
        try {
            AntennaInfo info = antenna.selfTest();
            selfTestPassed = true;
            LOG.info("Antenna {} self-test PASS", antennaId);
            return info;
        } catch (RuntimeException ex) {
            selfTestPassed = false;
            recordFailure(ex);
            LOG.warn("Antenna {} self-test FAIL", antennaId, ex);
            throw ex;
        }
    }

    /**
     * Marks the start of the prepare sequence owned by {@code InventoryTask}.
     */
    public void beginInventoryPreparation() {
        checkState(availableForInventory(), "Antenna %s is not available for inventory", antennaId);
        checkState(operation != AntennaOperation.INVENTORY, "Antenna %s is already inventorying", antennaId);
        checkState(operation != AntennaOperation.READY, "Antenna %s is already prepared", antennaId);
        checkState(operation != AntennaOperation.PREPARING, "Antenna %s is already preparing", antennaId);
        operation = AntennaOperation.PREPARING;
    }

    public void initialize() {
        checkState(availableForInventory(), "Antenna %s is not available for initialization", antennaId);
        checkState(operation == AntennaOperation.PREPARING,
                "Antenna %s cannot initialize from %s", antennaId, operation);

        try {
            antenna.initialize();
            operation = AntennaOperation.READY;
            LOG.debug("Antenna {} prepared for inventory", antennaId);
        } catch (RuntimeException ex) {
            operation = AntennaOperation.INACTIVE;
            recordFailure(ex);
            throw ex;
        }
    }

    public void startInventory() {
        if (operation == AntennaOperation.INVENTORY) {
            return;
        }

        checkState(availableForInventory(), "Antenna %s is not available for inventory", antennaId);
        checkState(operation == AntennaOperation.READY,
                "Antenna %s cannot start inventory from %s", antennaId, operation);

        try {
            antenna.startInventory();
            operation = AntennaOperation.INVENTORY;
            LOG.info("Antenna {} inventory started", antennaId);
        } catch (RuntimeException ex) {
            recordFailure(ex);
            throw ex;
        }
    }

    public void stopInventory() {
        if (operation != AntennaOperation.INVENTORY) {
            return;
        }

        try {
            antenna.stopInventory();
            operation = AntennaOperation.READY;
            LOG.info("Antenna {} inventory stopped", antennaId);
        } catch (RuntimeException ex) {
            recordFailure(ex);
            throw ex;
        }
    }

    public void shutdownProvider() {
        try {
            antenna.shutdown();
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
