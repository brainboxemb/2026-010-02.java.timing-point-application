package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

/**
 * Built-in deterministic antenna implementation for simulation and verification.
 *
 * <p>The Step-5 implementation has no hidden worker. Calling {@link #emit}
 * invokes the configured observation callback on the caller thread. The normal
 * TagProcessor/TimingNode boundary decides whether later state-changing work is
 * admitted to the bounded TimingNode lane.</p>
 */
public final class SimulatedAntenna implements Antenna {
    private ObservationListener listener;
    private boolean running;
    private boolean closed;

    @Override
    public synchronized void start(ObservationListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (closed) {
            throw new IllegalStateException("SimulatedAntenna is closed");
        }
        if (running) {
            throw new IllegalStateException("SimulatedAntenna is already running");
        }
        this.listener = listener;
        running = true;
    }

    @Override
    public synchronized boolean running() {
        return running;
    }

    /** Emits one deterministic decoded observation. */
    public void emit(String tagId, TimingTimestamp time) {
        ObservationListener current;
        synchronized (this) {
            if (!running || listener == null) {
                throw new IllegalStateException("SimulatedAntenna is not running");
            }
            current = listener;
        }
        current.onObservation(new Observation(tagId, time));
    }

    @Override
    public synchronized void close() {
        running = false;
        listener = null;
        closed = true;
    }
}
