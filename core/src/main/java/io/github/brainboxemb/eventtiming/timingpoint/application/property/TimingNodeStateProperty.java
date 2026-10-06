package io.github.brainboxemb.eventtiming.timingpoint.application.property;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.TrackedProperty;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

import java.util.function.Consumer;

/**
 * Application property that tracks the current authoritative TimingNode
 * state.
 *
 * <p>TimingNode status events are only change signals. The event's Status value
 * is deliberately ignored; the underlying TrackedProperty rereads
 * TimingNodeQueries.status() on the Application lane.</p>
 */
public final class TimingNodeStateProperty {
    private final TrackedProperty<State> property;

    public TimingNodeStateProperty(
            TimingNode timingNode,
            SerialExecutor applicationLane) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }

        property =
                new TrackedProperty<State>(
                        "TimingNode.state",
                        applicationLane,
                        () -> timingNode
                                .query(
                                        TimingNodeQueries.status())
                                .state());
    }

    public void onChange(
            Consumer<State> handler) {
        property.onChange(handler);
    }

    public void initialize() {
        property.initialize();
    }

    /**
     * Listener used for direct Runtime wiring from TimingNode.statusChangedEvent().
     */
    public Consumer<Status> changeSignal() {
        return ignored -> property.signalChanged();
    }

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
