package io.github.brainboxemb.eventtiming.timingpoint.application.property;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.TrackedProperty;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

/**
 * Application property that tracks the current authoritative TimingNode
 * state.
 *
 * <p>TimingNode status events are only change signals. The event's Status value
 * is deliberately ignored; the underlying TrackedProperty rereads
 * TimingNodeQueries.status() on the Application lane.</p>
 */
public final class TimingNodeStateProperty {
    private final TimingNode timingNode;
    private final TrackedProperty<State> property;

    public TimingNodeStateProperty(
            TimingNode timingNode,
            SerialExecutor applicationLane) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }

        this.timingNode = timingNode;

        /*
         * Construction only wires the deferred reader. The TimingNode query is
         * performed later by TrackedProperty.initialize()/refresh on the
         * Application lane.
         */
        property =
                new TrackedProperty<State>(
                        "TimingNode.state",
                        applicationLane,
                        this::readTimingNodeState);
    }

    /**
     * Reads current state through the TimingNode serialized query boundary.
     *
     * <p>The underlying status read is a small in-memory snapshot. The query
     * itself still enters the TimingNode serial lane and waits for its turn, so
     * this method must remain an explicit runtime read rather than constructor
     * work.</p>
     */
    private State readTimingNodeState() {
        return timingNode
                .query(
                        TimingNodeQueries.status())
                .state();
    }

    /**
     * Event emitted after initialization when the authoritative TimingNode state
     * really changes.
     */
    public EventSource<State> changedEvent() {
        return property.changedEvent();
    }

    /**
     * Reads and stores the initial authoritative TimingNode state.
     *
     * <p>The initial value is returned explicitly and is not emitted as a
     * changedEvent.</p>
     */
    public State initialize() {
        return property.initialize();
    }

    /**
     * Signals that TimingNode status may have changed.
     *
     * <p>The Status event payload is intentionally not used as authority. The
     * underlying tracked property rereads TimingNodeQueries.status().state().</p>
     */
    public boolean signalChanged() {
        return property.signalChanged();
    }

    public boolean initialized() {
        return property.initialized();
    }

    public State currentValue() {
        return property.currentValue();
    }
}
