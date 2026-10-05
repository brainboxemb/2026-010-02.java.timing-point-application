package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Coordinates application-wide relationships between already constructed components.
 *
 * <p>The conductor deliberately does not construct components, own worker threads or
 * implement device/domain behaviour. Runtime composition creates the object graph;
 * this class makes the application-level relationships between those objects explicit.</p>
 *
 * <p>For the current single-node antenna path it owns two relationships:</p>
 * <ul>
 *   <li>antenna observations are forwarded to the node-local TagProcessor;</li>
 *   <li>TimingNode OPEN/CLOSED lifecycle determines whether the AntennaManager should
 *       provide inventory.</li>
 * </ul>
 *
 * <p>{@link #connect()} installs those relationships without starting components.
 * {@link #synchronize()} is called after component startup so AntennaManager receives
 * the TimingNode's current lifecycle once, even when no status-change event occurred
 * during startup.</p>
 */
public final class Conductor implements AutoCloseable {

    private final TimingNode timingNode;
    private final AntennaManager antennaManager;

    private final Consumer<TagObservation> observationListener;
    private final Consumer<Status> timingNodeStatusListener;
    private final List<Antenna> subscribedAntennas =
            new ArrayList<Antenna>();

    private boolean connected;

    public Conductor(
            TimingNode timingNode,
            AntennaManager antennaManager) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }

        this.timingNode = timingNode;
        this.antennaManager = antennaManager;
        observationListener =
                timingNode.tagProcessor()::onObservation;
        timingNodeStatusListener =
                this::onTimingNodeStatusChanged;
    }

    /**
     * Installs application relationships. No component or worker is started here.
     */
    public synchronized void connect() {
        if (connected) {
            throw new IllegalStateException(
                    "Conductor is already connected");
        }

        if (antennaManager == null) {
            connected = true;
            return;
        }

        if (!timingNode.statusChangedEvent()
                .subscribe(timingNodeStatusListener)) {
            throw new IllegalStateException(
                    "TimingNode lifecycle listener was already subscribed");
        }

        try {
            for (Antenna antenna : antennaManager.antennas()) {
                if (!antenna.observations()
                        .subscribe(observationListener)) {
                    throw new IllegalStateException(
                            "Antenna observation listener was already subscribed");
                }
                subscribedAntennas.add(antenna);
            }
            connected = true;
        } catch (RuntimeException ex) {
            disconnectInternal();
            throw ex;
        }
    }

    /**
     * Reconciles cross-component state after the involved components have started.
     */
    public void synchronize() {
        if (antennaManager == null) {
            return;
        }

        synchronized (this) {
            if (!connected) {
                throw new IllegalStateException(
                        "Conductor must be connected before synchronization");
            }
        }

        Status current = timingNode.query(
                TimingNodeQueries.status());
        applyTimingNodeLifecycle(current);
    }

    private void onTimingNodeStatusChanged(
            Status status) {
        applyTimingNodeLifecycle(status);
    }

    private void applyTimingNodeLifecycle(
            Status status) {
        antennaManager.requestOperational(
                status.lifecycle() == Lifecycle.OPEN);
    }

    /**
     * Removes the relationships installed by {@link #connect()}.
     */
    @Override
    public synchronized void close() {
        disconnectInternal();
    }

    private void disconnectInternal() {
        if (antennaManager != null) {
            timingNode.statusChangedEvent()
                    .unsubscribe(timingNodeStatusListener);

            for (Antenna antenna : subscribedAntennas) {
                antenna.observations()
                        .unsubscribe(observationListener);
            }
            subscribedAntennas.clear();
        }
        connected = false;
    }
}
