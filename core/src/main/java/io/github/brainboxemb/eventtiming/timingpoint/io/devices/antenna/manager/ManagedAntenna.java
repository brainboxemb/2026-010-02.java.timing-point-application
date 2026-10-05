package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaPowerControl;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaState;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Runtime owner of one configured physical antenna.
 *
 * <p>This class contains the complete single-antenna hardware sequence:
 * external power, stabilization, probe, initialize, inventory start/stop and
 * close. It has no knowledge of executors, application lifecycle or multiplex
 * scheduling.</p>
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
     * Health-checks the provider and leaves externally switched hardware off.
     */
    void probe() {
        state = AntennaState.CHECKING;
        failure = null;

        try {
            powerOn();
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
     * Powers and initializes this antenna, but does not start inventory.
     */
    boolean prepareForInventory() {
        if (state == AntennaState.ERROR
                || state == AntennaState.CLOSED) {
            return false;
        }
        if (state == AntennaState.INVENTORY) {
            return true;
        }

        try {
            powerOn();
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

    private void powerOn() {
        AntennaPowerControl power =
                installation.powerControl();

        if (power == null
                || externalPowerApplied) {
            return;
        }

        power.powerOn();
        externalPowerApplied = true;
        waitForStabilization(
                installation.powerStabilization());
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

    private static void waitForStabilization(
            Duration delay) {
        if (delay.isZero()) {
            return;
        }

        try {
            TimeUnit.NANOSECONDS.sleep(
                    delay.toNanos());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Antenna power stabilization was interrupted",
                    ex);
        }
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
