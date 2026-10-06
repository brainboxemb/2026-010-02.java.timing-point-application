package io.github.brainboxemb.eventtiming.timingpoint.application.property;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.TrackedProperty;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

import java.util.function.Consumer;

/**
 * Application property that tracks the current authoritative TimingNode
 * lifecycle.
 *
 * <p>TimingNode status events are only change signals. The event's Status value
 * is deliberately ignored; the underlying TrackedProperty rereads
 * TimingNodeQueries.status() on the Application lane.</p>
 */
public final class TimingNodeLifecycleProperty {
    private final TrackedProperty<Lifecycle> property;

    public TimingNodeLifecycleProperty(
            TimingNode timingNode,
            SerialExecutor applicationLane) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }

        property =
                new TrackedProperty<Lifecycle>(
                        "TimingNode.lifecycle",
                        applicationLane,
                        () -> timingNode
                                .query(
                                        TimingNodeQueries.status())
                                .lifecycle());
    }

    public void onChange(
            Consumer<Lifecycle> handler) {
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

    public Lifecycle currentValue() {
        return property.currentValue();
    }
}
