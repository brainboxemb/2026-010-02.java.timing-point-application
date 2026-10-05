package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManagerTypes.ControlException;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManagerTypes.FailureReason;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Lifecycle and execution boundary for the configured antennas of one TimingSystem.
 *
 * <p>Device/power/multiplex state lives in {@link AntennaManagerLogic}. Control
 * operations are serialized by one supplied {@link SerialExecutor}; this class
 * does not implement or own another physical worker/queue implementation.</p>
 */
public final class AntennaManager {

    private final AntennaManagerLogic logic;
    private final SerialExecutor controlLane;
    private final ScheduledExecutorService scheduler;
    private final long controlTimeoutNanos;

    private volatile State state = State.NEW;
    private volatile Throwable failure;
    private volatile boolean inventoryEnabledRequested;

    private ScheduledFuture<?> rotationSchedule;

    public AntennaManager(
            List<AntennaInstallation> installations,
            SerialExecutor controlLane,
            ScheduledExecutorService scheduler,
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

        logic = new AntennaManagerLogic(installations);
        if (logic.hasInventoryGroup()
                && scheduler == null) {
            throw new IllegalArgumentException(
                    "inventory group requires a scheduler");
        }

        this.controlLane = controlLane;
        this.scheduler = scheduler;
        try {
            controlTimeoutNanos =
                    controlTimeout.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "controlTimeout is too large",
                    ex);
        }
    }

    /**
     * Activates this manager.
     *
     * <p>Activation starts the manager's logical control lane and probes every
     * configured antenna once. It does not enable inventory. Whether antennas
     * may read tags is controlled separately through
     * {@link #requestInventoryEnabled(boolean)}.</p>
     */
    public void activate() {
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException(
                        "AntennaManager can only activate from NEW; current state="
                                + state);
            }
            state = State.STARTING;
        }

        try {
            controlLane.start();
            runControl(logic::probeAll);
            refreshAggregateState();
        } catch (RuntimeException ex) {
            failure = ex;
            state = State.FAILED;
            throw ex;
        }
    }

    /**
     * Requests the latest desired inventory-enabled state without waiting for provider I/O.
     *
     * <p>Used from TimingNode status callbacks. ACCEPTED means only that the
     * reconcile operation entered this manager's bounded serial lane.</p>
     */
    public boolean requestInventoryEnabled(
            boolean operational) {
        State current = state;
        if (current != State.RUNNING
                && current != State.DEGRADED) {
            return false;
        }

        inventoryEnabledRequested = operational;
        SerialExecutor.AdmissionResult admission =
                controlLane.offer(this::reconcileInventoryEnabled);
        if (admission == SerialExecutor.AdmissionResult.ACCEPTED) {
            return true;
        }

        recordControlFailure(
                controlFailure(
                        FailureReason.OVERLOADED,
                        "AntennaManager control lane rejected inventory-enable work: "
                                + admission,
                        controlLane.failure()));
        return false;
    }

    /**
     * Result-bearing form of the same inventory-enable request.
     *
     * <p>Enabling inventory keeps the physical sequence internal to the manager:
     * apply external power when configured, wait for stabilization, initialize
     * the antenna, then start inventory according to the multiplex policy.</p>
     */
    public void setInventoryEnabled(
            boolean operational) {
        State current = state;
        if (current != State.RUNNING
                && current != State.DEGRADED) {
            throw new IllegalStateException(
                    "AntennaManager is not active for inventory control; current state="
                            + current);
        }

        inventoryEnabledRequested = operational;
        runControl(this::reconcileInventoryEnabled);
    }

    public State state() {
        return state;
    }

    public Throwable failure() {
        Throwable controlFailure = failure;
        return controlFailure != null
                ? controlFailure
                : logic.failure();
    }

    /**
     * Returns the subscription-only event for one configured antenna.
     *
     * <p>This accessor is intended for composition-time wiring and may be called
     * before {@link #start()}. The concrete Antenna remains owned by this manager.</p>
     */
    public EventSource<TagObservation> tagObservedEvent(
            AntennaId antennaId) {
        return logic.tagObservedEvent(antennaId);
    }

    public List<AntennaStatus> statuses() {
        return logic.statuses();
    }

    public AntennaStatus status(
            AntennaId antennaId) {
        return logic.status(antennaId);
    }

    public void deactivate() {
        State current;
        synchronized (this) {
            current = state;
            if (current == State.STOPPED) {
                return;
            }
            if (current == State.STARTING
                    || current == State.STOPPING) {
                throw new IllegalStateException(
                        "AntennaManager cannot close during transition; current state="
                                + current);
            }
            state = State.STOPPING;
        }

        inventoryEnabledRequested = false;
        cancelRotationSchedule();

        RuntimeException closeFailure = null;
        try {
            if (controlLane.state()
                    == SerialExecutor.State.NEW) {
                controlLane.start();
            }
            runControl(logic::stopPowerAndCloseAll);
        } catch (RuntimeException ex) {
            closeFailure = ex;
        } finally {
            try {
                controlLane.close();
            } catch (RuntimeException ex) {
                if (closeFailure == null) {
                    closeFailure = ex;
                }
            }
        }

        if (closeFailure == null) {
            state = State.STOPPED;
        } else {
            failure = closeFailure;
            state = State.FAILED;
            throw closeFailure;
        }
    }

    private void reconcileInventoryEnabled() {
        if (inventoryEnabledRequested) {
            logic.activateAvailableAntennas();
            ensureRotationSchedule();
        } else {
            cancelRotationSchedule();
            logic.deactivateAntennas();
        }
        refreshAggregateState();
    }

    private synchronized void ensureRotationSchedule() {
        if (!inventoryEnabledRequested
                || !logic.hasInventoryGroup()
                || !logic.inventoryGroupNeedsRotation()) {
            cancelRotationSchedule();
            return;
        }

        if (rotationSchedule != null
                && !rotationSchedule.isCancelled()) {
            return;
        }

        long intervalNanos =
                logic.inventoryInterval().toNanos();
        rotationSchedule =
                scheduler.scheduleWithFixedDelay(
                        this::requestGroupRotation,
                        intervalNanos,
                        intervalNanos,
                        TimeUnit.NANOSECONDS);
    }

    private void requestGroupRotation() {
        if (!inventoryEnabledRequested) {
            return;
        }

        SerialExecutor.AdmissionResult admission =
                controlLane.offer(this::rotateGroup);
        if (admission
                != SerialExecutor.AdmissionResult.ACCEPTED) {
            recordControlFailure(
                    controlFailure(
                            FailureReason.OVERLOADED,
                            "AntennaManager control lane rejected inventory rotation: "
                                    + admission,
                            controlLane.failure()));
        }
    }

    private void rotateGroup() {
        if (!inventoryEnabledRequested) {
            return;
        }

        logic.rotateInventoryGroup();
        if (!logic.inventoryGroupNeedsRotation()) {
            cancelRotationSchedule();
        }
        refreshAggregateState();
    }

    private synchronized void cancelRotationSchedule() {
        ScheduledFuture<?> schedule =
                rotationSchedule;
        rotationSchedule = null;
        if (schedule != null) {
            schedule.cancel(false);
        }
    }

    private void refreshAggregateState() {
        State current = state;
        if (current == State.STOPPING
                || current == State.STOPPED) {
            return;
        }
        state = logic.aggregateState();
    }

    private void runControl(
            Runnable action) {
        SerialExecutor.SubmitResult<Void> submission =
                controlLane.submit(() -> {
                    action.run();
                    return null;
                });

        switch (submission.admission()) {
            case FULL:
                throw controlFailure(
                        FailureReason.OVERLOADED,
                        "AntennaManager control lane is full",
                        null);
            case NOT_RUNNING:
                throw controlFailure(
                        FailureReason.OVERLOADED,
                        "AntennaManager control lane is not running",
                        controlLane.failure());
            case ACCEPTED:
                await(submission.futureResult());
                return;
            default:
                throw new IllegalStateException(
                        "Unsupported control admission "
                                + submission.admission());
        }
    }

    private void await(
            Future<Void> future) {
        try {
            future.get(
                    controlTimeoutNanos,
                    TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw controlFailure(
                    FailureReason.TIMEOUT,
                    "AntennaManager control operation timed out",
                    ex);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw controlFailure(
                    FailureReason.INTERRUPTED,
                    "AntennaManager control operation was interrupted",
                    ex);
        } catch (CancellationException ex) {
            throw controlFailure(
                    FailureReason.OVERLOADED,
                    "AntennaManager control operation was cancelled before completion",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw controlFailure(
                    FailureReason.PROVIDER_FAILURE,
                    "AntennaManager provider operation failed",
                    cause == null ? ex : cause);
        }
    }

    private void recordControlFailure(
            Throwable cause) {
        if (failure == null) {
            failure = cause;
        }
    }

    private static ControlException controlFailure(
            FailureReason reason,
            String message,
            Throwable cause) {
        return new ControlException(
                reason,
                message,
                cause);
    }
}
