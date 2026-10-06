package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaPowerControl;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaHealth;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaOperation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the device state of one configured physical antenna.
 *
 * <p>Health and operation are deliberately separate. For example, after a
 * successful startup probe an externally powered antenna is HEALTHY but
 * operationally INACTIVE because probe power has already been removed.</p>
 *
 * <p>AntennaManager calls this object only from its one serial control lane.
 * The object therefore needs no internal locking and owns no scheduler.</p>
 */
final class ManagedAntenna {
    private static final Logger LOG =
            LoggerFactory.getLogger(ManagedAntenna.class);

    private final AntennaInstallation installation;

    private volatile AntennaHealth health =
            AntennaHealth.UNKNOWN;
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
                health,
                operation,
                failure);
    }

    Throwable failure() {
        return failure;
    }

    boolean healthy() {
        return health == AntennaHealth.HEALTHY;
    }

    boolean inventoryRunning() {
        return operation == AntennaOperation.INVENTORY;
    }

    /**
     * Starts one health probe by applying external power when required.
     *
     * @return stabilization delay before probe completion, or {@code null} when
     *         power preparation failed and the probe must be skipped
     */
    Duration beginProbe() {
        if (operation == AntennaOperation.SHUTDOWN) {
            return null;
        }

        health = AntennaHealth.CHECKING;
        failure = null;

        try {
            return powerOn();
        } catch (RuntimeException ex) {
            fail(
                    "health-check power-on",
                    ex);
            powerOffAfterFailure();
            return null;
        } catch (Error error) {
            fail(
                    "health-check power-on",
                    error);
            powerOffAfterFailure();
            return null;
        }
    }

    /**
     * Completes a health probe after any required stabilization delay.
     */
    void completeProbe() {
        if (operation == AntennaOperation.SHUTDOWN) {
            return;
        }

        try {
            installation.antenna().probe();
            health = AntennaHealth.HEALTHY;
            LOG.info(
                    "Antenna {} health check succeeded",
                    antennaId());
        } catch (RuntimeException ex) {
            fail(
                    "health check",
                    ex);
        } catch (Error error) {
            fail(
                    "health check",
                    error);
        } finally {
            powerOffAfterProbe();
            if (operation != AntennaOperation.SHUTDOWN) {
                operation = AntennaOperation.INACTIVE;
            }
        }
    }

    /**
     * Records a manager-level timeout/failure for this antenna health check.
     *
     * <p>This is used only after the manager has cancelled the result-bearing
     * provider operation. The serial lane still orders later work.</p>
     */
    void healthCheckFailed(
            Throwable cause) {
        fail(
                "health check control",
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
    Duration beginPrepareForInventory() {
        if (!healthy()
                || operation == AntennaOperation.SHUTDOWN
                || operation == AntennaOperation.INVENTORY
                || operation == AntennaOperation.READY
                || operation == AntennaOperation.PREPARING) {
            return null;
        }

        operation = AntennaOperation.PREPARING;

        try {
            return powerOn();
        } catch (RuntimeException ex) {
            fail(
                    "inventory power-on",
                    ex);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return null;
        } catch (Error error) {
            fail(
                    "inventory power-on",
                    error);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return null;
        }
    }

    /**
     * Initializes this antenna after stabilization.
     */
    boolean completePrepareForInventory() {
        if (!healthy()
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
            fail(
                    "initialize",
                    ex);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return false;
        } catch (Error error) {
            fail(
                    "initialize",
                    error);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return false;
        }
    }

    /**
     * Starts provider inventory when this antenna is prepared and healthy.
     */
    boolean startInventory() {
        if (operation == AntennaOperation.INVENTORY) {
            return true;
        }
        if (!healthy()
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
            fail(
                    "start inventory",
                    ex);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return false;
        } catch (Error error) {
            fail(
                    "start inventory",
                    error);
            operation = AntennaOperation.INACTIVE;
            powerOffAfterFailure();
            return false;
        }
    }

    /**
     * Stops provider inventory but keeps a healthy antenna prepared.
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
            fail(
                    "stop inventory",
                    ex);
            return false;
        } catch (Error error) {
            fail(
                    "stop inventory",
                    error);
            return false;
        }
    }

    /**
     * Stops normal inventory use and removes external power when configured.
     */
    void disableInventory() {
        boolean stopped =
                stopInventory();

        try {
            powerOff();
            if (stopped
                    || installation.powerControl() != null) {
                operation = AntennaOperation.INACTIVE;
            }
        } catch (RuntimeException ex) {
            fail(
                    "inventory power-off",
                    ex);
        }
    }

    /**
     * Releases all provider/device resources owned by this configured antenna.
     */
    void shutdown() {
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
            fail(
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

    /**
     * Applies external power and returns the required stabilization delay.
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
        LOG.debug(
                "Antenna {} external power enabled",
                antennaId());
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
            LOG.debug(
                    "Antenna {} external power disabled",
                    antennaId());
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
            fail(
                    "health-check power-off",
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

    private void fail(
            String action,
            Throwable cause) {
        if (failure == null) {
            failure = cause;
        } else if (failure != cause) {
            failure.addSuppressed(
                    cause);
        }

        health = AntennaHealth.FAILED;

        LOG.warn(
                "Antenna {} failed during {}",
                antennaId(),
                action,
                cause);
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
