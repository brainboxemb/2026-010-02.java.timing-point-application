package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaPowerControl;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaState;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;

/**
 * Runtime owner of one configured physical antenna.
 *
 * <p>This class owns the single-antenna hardware state and provider steps:
 * external power, probe, initialize, inventory start/stop and close. Power
 * stabilization is exposed as an elapsed-time requirement between begin and
 * complete steps; this object never sleeps or owns scheduling.</p>
 *
 * <p>All methods are called by {@link AntennaSwitchController} on the manager's
 * one serial control lane, so this class needs no internal locking.</p>
 */
final class ManagedAntenna {

    private final AntennaInstallation installation;

    private AntennaState state =
            AntennaState.UNCHECKED;
    private Throwable failure;
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

    boolean inInventoryGroup() {
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
                state,
                failure);
    }

    Throwable failure() {
        return failure;
    }

    boolean healthy() {
        return state == AntennaState.READY
                || state == AntennaState.INVENTORY;
    }

    boolean inventoryRunning() {
        return state == AntennaState.INVENTORY;
    }

    /**
     * Begins a probe by applying external power when required.
     *
     * @return the required stabilization delay, or {@code null} when power
     *         preparation failed and the probe must be skipped
     */
    Duration beginProbe() {
        state = AntennaState.CHECKING;
        failure = null;

        try {
            return powerOn();
        } catch (RuntimeException ex) {
            fail(ex);
            powerOffAfterFailure();
            return null;
        } catch (Error ex) {
            fail(ex);
            powerOffAfterFailure();
            return null;
        }
    }

    /**
     * Completes a probe after any required stabilization delay has elapsed.
     */
    void completeProbe() {
        try {
            installation.antenna().probe();
            state = AntennaState.READY;
        } catch (RuntimeException ex) {
            fail(ex);
        } catch (Error ex) {
            fail(ex);
        } finally {
            powerOffAfterProbe();
        }
    }

    /**
     * Begins inventory preparation by applying external power when required.
     *
     * @return the required stabilization delay, or {@code null} when this
     *         antenna cannot currently be prepared
     */
    Duration beginPrepareForInventory() {
        if (state == AntennaState.ERROR
                || state == AntennaState.CLOSED
                || state == AntennaState.INVENTORY) {
            return null;
        }

        try {
            return powerOn();
        } catch (RuntimeException ex) {
            fail(ex);
            powerOffAfterFailure();
            return null;
        } catch (Error ex) {
            fail(ex);
            powerOffAfterFailure();
            return null;
        }
    }

    /**
     * Initializes this antenna after any required stabilization delay.
     */
    boolean completePrepareForInventory() {
        if (state == AntennaState.ERROR
                || state == AntennaState.CLOSED
                || state == AntennaState.INVENTORY) {
            return false;
        }

        try {
            installation.antenna().initialize();
            state = AntennaState.READY;
            return true;
        } catch (RuntimeException ex) {
            fail(ex);
            powerOffAfterFailure();
            return false;
        } catch (Error ex) {
            fail(ex);
            powerOffAfterFailure();
            return false;
        }
    }

    /**
     * Starts provider inventory when this antenna is prepared and healthy.
     */
    boolean startInventory() {
        if (state == AntennaState.INVENTORY) {
            return true;
        }
        if (state != AntennaState.READY) {
            return false;
        }

        try {
            installation.antenna().startInventory();
            state = AntennaState.INVENTORY;
            return true;
        } catch (RuntimeException ex) {
            fail(ex);
            powerOffAfterFailure();
            return false;
        } catch (Error ex) {
            fail(ex);
            powerOffAfterFailure();
            return false;
        }
    }

    /**
     * Stops provider inventory but keeps the antenna prepared/powered.
     *
     * <p>Keeping power applied is intentional during multiplex rotation; the
     * next inventory interval can therefore switch quickly.</p>
     */
    void stopInventory() {
        if (state != AntennaState.INVENTORY) {
            return;
        }

        try {
            installation.antenna().stopInventory();
            state = AntennaState.READY;
        } catch (RuntimeException ex) {
            fail(ex);
        } catch (Error ex) {
            fail(ex);
        }
    }

    /**
     * Stops inventory and removes external power for normal inventory-disable.
     */
    void disableInventory() {
        stopInventory();
        powerOff();
    }

    /**
     * Releases all physical resources owned by this configured antenna.
     */
    void close() {
        RuntimeException firstFailure = null;

        try {
            stopInventory();
        } catch (RuntimeException ex) {
            firstFailure = ex;
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
            installation.antenna().close();
        } catch (RuntimeException ex) {
            firstFailure =
                    appendFailure(
                            firstFailure,
                            ex);
        }

        state = AntennaState.CLOSED;

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /**
     * Applies external power without occupying the worker for stabilization.
     *
     * @return the delay that must elapse before provider I/O may continue
     */
    private Duration powerOn() {
        AntennaPowerControl power =
                installation.powerControl();

        if (power == null
                || externalPowerApplied) {
            return Duration.ZERO;
        }

        power.powerOn();
        externalPowerApplied = true;
        return installation.powerStabilization();
    }

    private void powerOff() {
        AntennaPowerControl power =
                installation.powerControl();

        if (power == null
                || !externalPowerApplied) {
            return;
        }

        try {
            power.powerOff();
        } finally {
            externalPowerApplied = false;
        }
    }

    private void powerOffAfterProbe() {
        if (installation.powerControl() == null) {
            return;
        }

        try {
            powerOff();
        } catch (RuntimeException ex) {
            fail(ex);
        }
    }

    private void powerOffAfterFailure() {
        try {
            powerOff();
        } catch (RuntimeException ignored) {
            // Keep the original provider failure as the diagnostic cause.
        }
    }

    private void fail(
            Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
        state = AntennaState.ERROR;
    }

    private static RuntimeException appendFailure(
            RuntimeException current,
            RuntimeException later) {
        if (current == null) {
            return later;
        }
        current.addSuppressed(later);
        return current;
    }
}
