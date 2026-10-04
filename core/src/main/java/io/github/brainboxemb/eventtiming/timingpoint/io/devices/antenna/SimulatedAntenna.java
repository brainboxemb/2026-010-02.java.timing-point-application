package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/** Built-in deterministic antenna implementation for simulation and verification. */
public final class SimulatedAntenna implements Antenna {
    private static final AntennaInfo INFO =
            new AntennaInfo("simulated-antenna", "1");

    private final Event<TagObservation> observationEvent = new Event<>();

    private boolean initialized;
    private boolean inventoryRunning;
    private boolean closed;

    @Override
    public synchronized AntennaInfo probe() {
        requireOpen();
        return INFO;
    }

    @Override
    public synchronized void initialize() {
        requireOpen();
        if (inventoryRunning) {
            throw new IllegalStateException(
                    "SimulatedAntenna cannot initialize while inventory is running");
        }
        initialized = true;
    }

    @Override
    public synchronized void startInventory() {
        requireOpen();
        if (!initialized) {
            throw new IllegalStateException(
                    "SimulatedAntenna must be initialized before inventory starts");
        }
        if (inventoryRunning) {
            throw new IllegalStateException(
                    "SimulatedAntenna inventory is already running");
        }
        inventoryRunning = true;
    }

    @Override
    public synchronized void stopInventory() {
        requireOpen();
        inventoryRunning = false;
    }

    @Override
    public synchronized boolean inventoryRunning() {
        return inventoryRunning;
    }

    @Override
    public EventSource<TagObservation> observations() {
        return observationEvent;
    }

    /** Emits one deterministic observation on the calling thread. */
    public void emit(TagObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }
        synchronized (this) {
            requireOpen();
            if (!inventoryRunning) {
                throw new IllegalStateException(
                        "SimulatedAntenna inventory is not running");
            }
        }
        observationEvent.emit(observation);
    }

    /** Convenience overload for deterministic tests and simulation controls. */
    public void emit(
            DecryptedTagId tagId,
            int rssi,
            TimingTimestamp observedAt) {
        emit(new TagObservation(tagId, rssi, observedAt));
    }

    @Override
    public synchronized void close() {
        inventoryRunning = false;
        initialized = false;
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("SimulatedAntenna is closed");
        }
    }
}
