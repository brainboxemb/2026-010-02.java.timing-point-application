package oss.relay.eventtiming.timingpoint.platform.events;

import oss.relay.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Typed synchronous Event that records delivery duration and listener failures.
 *
 * <p>Use this variant only when event-delivery measurements are required. Plain
 * {@link Event} remains the zero-metrics event implementation.</p>
 */
public final class EventWithMetrics<T> implements EventSource<T> {
    private final Event<T> event = new Event<T>();
    private final MonotonicClock monotonicClock;
    private final EventMetrics metrics;

    public EventWithMetrics(
            MonotonicClock monotonicClock,
            EventMetrics metrics) {
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
    }

    @Override
    public boolean subscribe(Consumer<T> listener) {
        return event.subscribe(listener);
    }

    @Override
    public boolean subscribe(Predicate<T> filter, Consumer<T> listener) {
        return event.subscribe(filter, listener);
    }

    /** Emits one value synchronously and records its complete delivery cost. */
    public Event.DeliveryReport emit(T value) {
        long startedNanos = monotonicClock.nowNanos();
        Event.DeliveryReport delivery = event.emit(value);
        metrics.record(
                monotonicClock.nowNanos() - startedNanos,
                delivery.failureCount());
        return delivery;
    }

    /** Returns the metrics source owned by this measured event. */
    public EventMetrics metrics() {
        return metrics;
    }
}
