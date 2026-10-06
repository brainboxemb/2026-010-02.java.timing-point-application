package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/** Built-in deterministic antenna implementation for simulation and verification. */
public final class SimulatedAntenna implements Antenna {
    public enum FailurePoint {
        NONE,
        PROBE,
        INITIALIZE,
        START_INVENTORY,
        STOP_INVENTORY
    }

    private static final AntennaInfo INFO =
            new AntennaInfo("simulated-antenna", "1");

    private final Event<TagObservation> observationEvent = new Event<>();

    private boolean initialized;
    private boolean inventoryRunning;
    private boolean shutdown;
    private boolean externalPowerControlled;
    private boolean powered = true;
    private FailurePoint failurePoint = FailurePoint.NONE;
    private int inventoryStartCount;

    @Override
    public synchronized AntennaInfo probe() {
        requireOpen();
        requirePowered();
        failIf(FailurePoint.PROBE);
        return INFO;
    }

    @Override
    public synchronized void initialize() {
        requireOpen();
        requirePowered();
        failIf(FailurePoint.INITIALIZE);
        if (inventoryRunning) {
            throw new IllegalStateException(
                    "SimulatedAntenna cannot initialize while inventory is running");
        }
        initialized = true;
    }

    @Override
    public synchronized void startInventory() {
        requireOpen();
        requirePowered();
        failIf(FailurePoint.START_INVENTORY);
        if (!initialized) {
            throw new IllegalStateException(
                    "SimulatedAntenna must be initialized before inventory starts");
        }
        if (inventoryRunning) {
            throw new IllegalStateException(
                    "SimulatedAntenna inventory is already running");
        }
        inventoryRunning = true;
        inventoryStartCount++;
    }

    @Override
    public synchronized void stopInventory() {
        requireOpen();
        failIf(FailurePoint.STOP_INVENTORY);
        inventoryRunning = false;
    }

    @Override
    public synchronized boolean inventoryRunning() {
        return inventoryRunning;
    }

    @Override
    public EventSource<TagObservation> tagObservedEvent() {
        return observationEvent;
    }

    /** Emits one deterministic observation on the calling thread. */
    public void emit(TagObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }
        synchronized (this) {
            requireOpen();
            requirePowered();
            if (!inventoryRunning) {
                throw new IllegalStateException(
                        "SimulatedAntenna inventory is not running");
            }
        }
        observationEvent.emit(observation);
    }

    /** Convenience overload for deterministic tests and simulation controls. */
    public void emit(
            TagId tagId,
            int rssi,
            TimingTimestamp observedAt) {
        emit(new TagObservation(tagId, rssi, observedAt));
    }

    public synchronized void setFailurePoint(FailurePoint failurePoint) {
        if (failurePoint == null) {
            throw new IllegalArgumentException("failurePoint must not be null");
        }
        this.failurePoint = failurePoint;
    }

    public synchronized void clearFailure() {
        failurePoint = FailurePoint.NONE;
    }

    public synchronized boolean powered() {
        return powered;
    }

    public synchronized int inventoryStartCount() {
        return inventoryStartCount;
    }

    @Override
    public synchronized void shutdown() {
        inventoryRunning = false;
        initialized = false;
        if (externalPowerControlled) {
            powered = false;
        }
        shutdown = true;
    }

    synchronized void attachExternalPowerControl() {
        requireOpen();
        if (externalPowerControlled) {
            throw new IllegalStateException(
                    "SimulatedAntenna already has external power control");
        }
        externalPowerControlled = true;
        powered = false;
        initialized = false;
        inventoryRunning = false;
    }

    synchronized void setExternallyPowered(boolean powered) {
        requireOpen();
        if (!externalPowerControlled) {
            throw new IllegalStateException(
                    "SimulatedAntenna has no external power control");
        }
        this.powered = powered;
        if (!powered) {
            inventoryRunning = false;
            initialized = false;
        }
    }

    private void failIf(FailurePoint point) {
        if (failurePoint == point) {
            throw new IllegalStateException(
                    "SimulatedAntenna configured failure at " + point);
        }
    }

    private void requirePowered() {
        if (!powered) {
            throw new IllegalStateException("SimulatedAntenna is not powered");
        }
    }

    private void requireOpen() {
        if (shutdown) {
            throw new IllegalStateException("SimulatedAntenna is shut down");
        }
    }
}
