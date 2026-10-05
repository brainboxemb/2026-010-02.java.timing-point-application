package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.FailureReason;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
import java.util.List;

/**
 * Public lifecycle and control boundary for one configured set of antennas.
 *
 * <p>The class intentionally has only three responsibilities:</p>
 * <ul>
 *   <li>serialize antenna control on one {@link SerialScheduledExecutor};</li>
 *   <li>expose activation, inventory-enable and status APIs;</li>
 *   <li>start/cancel the optional multiplex-rotation timer.</li>
 * </ul>
 *
 * <p>All physical switching is delegated to {@link AntennaSwitchController}.
 * The manager therefore contains no direct power, initialize or inventory
 * sequencing and it never sees a JDK ScheduledExecutorService.</p>
 */
public final class AntennaManager {

    private final AntennaSwitchController switching;
    private final AntennaControlLane control;

    private volatile State state = State.NEW;
    private volatile Throwable failure;
    private volatile boolean inventoryEnabledRequested;

    private SerialScheduledExecutor.ScheduledTask rotationTask;

    public AntennaManager(
            List<AntennaInstallation> installations,
            SerialScheduledExecutor controlLane,
            Duration controlTimeout) {
        if (controlLane == null) {
            throw new IllegalArgumentException(
                    "controlLane must not be null");
        }
        if (controlTimeout == null
                || controlTimeout.isZero()
                || controlTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "controlTimeout must be positive");
        }

        switching =
                new AntennaSwitchController(
                        installations);
        control =
                new AntennaControlLane(
                        controlLane,
                        controlTimeout);
    }

    /**
     * Activates the manager and probes every configured antenna once.
     *
     * <p>Activation does not enable tag inventory. Inventory permission is a
     * separate application decision made through
     * {@link #requestInventoryEnabled(boolean)}.</p>
     */
    public void activate() {
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException(
                        "AntennaManager can only activate from NEW; current state="
                                + state);
            }
            state = State.ACTIVATING;
        }

        try {
            control.start();
            control.run(switching::probeAll);
            refreshState();
        } catch (RuntimeException ex) {
            failure = ex;
            cleanupAfterActivationFailure(ex);
            state = State.FAILED;
            throw ex;
        }
    }

    /**
     * Requests inventory enable/disable without waiting for provider I/O.
     *
     * <p>{@code true} means that healthy antennas may read tags. The manager
     * performs any required power-on, stabilization and initialization
     * internally. {@code false} stops inventory and removes external power.</p>
     *
     * @return {@code true} when the reconcile operation entered the bounded
     *         control lane
     */
    public boolean requestInventoryEnabled(
            boolean enabled) {
        if (!acceptsInventoryControl()) {
            return false;
        }

        inventoryEnabledRequested = enabled;
        if (control.execute(
                this::reconcileInventoryEnabled)) {
            return true;
        }

        recordFailure(
                AntennaControlLane.failure(
                        FailureReason.OVERLOADED,
                        "AntennaManager control lane rejected inventory-enable work",
                        control.failure()));
        return false;
    }

    /**
     * Synchronous result-bearing form of
     * {@link #requestInventoryEnabled(boolean)}.
     */
    public void setInventoryEnabled(
            boolean enabled) {
        if (!acceptsInventoryControl()) {
            throw new IllegalStateException(
                    "AntennaManager is not active for inventory control; current state="
                            + state);
        }

        inventoryEnabledRequested = enabled;
        control.run(
                this::reconcileInventoryEnabled);
    }

    /**
     * Returns the subscription-only tag-observed event for one configured
     * antenna without exposing the concrete Antenna object.
     */
    public EventSource<TagObservation> tagObservedEvent(
            AntennaId antennaId) {
        return switching.tagObservedEvent(
                antennaId);
    }

    public State state() {
        return state;
    }

    public Throwable failure() {
        Throwable managerFailure = failure;
        return managerFailure != null
                ? managerFailure
                : switching.failure();
    }

    public List<AntennaStatus> statuses() {
        return switching.statuses();
    }

    public AntennaStatus status(
            AntennaId antennaId) {
        return switching.status(
                antennaId);
    }

    /**
     * Deactivates the manager and releases all antenna resources.
     *
     * <p>This method is also safe before successful activation. That property is
     * important for simple application rollback: the composition owner may
     * always deactivate constructed components in reverse order.</p>
     */
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

        inventoryEnabledRequested = false;
        cancelRotation();

        RuntimeException firstFailure = null;

        try {
            if (control.isNew()) {
                control.start();
            }
            control.run(
                    switching::closeAll);
        } catch (RuntimeException ex) {
            firstFailure = ex;
        }

        try {
            control.close();
        } catch (RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            } else {
                firstFailure.addSuppressed(ex);
            }
        }

        if (firstFailure == null) {
            state = State.INACTIVE;
            return;
        }

        failure = firstFailure;
        state = State.FAILED;
        throw firstFailure;
    }

    /**
     * Reconciles the latest requested inventory permission on the control lane.
     */
    private void reconcileInventoryEnabled() {
        if (inventoryEnabledRequested) {
            switching.enableInventory();
            ensureRotation();
        } else {
            cancelRotation();
            switching.disableInventory();
        }
        refreshState();
    }

    /**
     * Starts one fixed-delay rotation callback on this same serial control lane.
     *
     * <p>No second scheduler->executor hop is needed: SerialScheduledExecutor
     * already guarantees that the periodic callback is serialized with all
     * other manager control.</p>
     */
    private synchronized void ensureRotation() {
        if (!inventoryEnabledRequested
                || !switching.hasInventoryGroup()
                || !switching.rotationNeeded()) {
            cancelRotation();
            return;
        }
        if (rotationTask != null) {
            return;
        }

        rotationTask =
                control.scheduleWithFixedDelay(
                        this::rotateInventoryGroup,
                        switching.inventoryInterval());
    }

    private void rotateInventoryGroup() {
        if (!inventoryEnabledRequested) {
            return;
        }

        switching.rotateInventoryGroup();
        if (!switching.rotationNeeded()) {
            cancelRotation();
        }
        refreshState();
    }

    private synchronized void cancelRotation() {
        SerialScheduledExecutor.ScheduledTask task =
                rotationTask;
        rotationTask = null;
        if (task != null) {
            task.close();
        }
    }

    private boolean acceptsInventoryControl() {
        State current = state;
        return current == State.ACTIVE
                || current == State.DEGRADED;
    }

    private void refreshState() {
        State current = state;
        if (current == State.DEACTIVATING
                || current == State.INACTIVE) {
            return;
        }
        state = switching.aggregateState();
    }

    private void cleanupAfterActivationFailure(
            RuntimeException activationFailure) {
        try {
            if (control.isRunning()) {
                control.run(
                        switching::closeAll);
            }
        } catch (RuntimeException cleanupFailure) {
            activationFailure.addSuppressed(
                    cleanupFailure);
        }

        try {
            control.close();
        } catch (RuntimeException cleanupFailure) {
            activationFailure.addSuppressed(
                    cleanupFailure);
        }
    }

    private void recordFailure(
            Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
    }

}
