package oss.relay.timing.timingpoint.platform.events;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Subscription-only view of a local typed event.
 *
 * <p>The owner keeps the mutable {@link Event} so only that owner can decide
 * when a fact is emitted. Runtime composition uses this view to connect one or
 * more listeners before application activation.</p>
 *
 * <p>Subscription is a composition-time operation. Subscribing does not create
 * an asynchronous boundary: when the owning Event emits a value, listeners run
 * synchronously on the emitting thread, in subscription order. Fixed composition
 * may therefore establish a deliberate short-handler ordering between consumers.
 * A listener that needs slower work must queue that work itself and return quickly.</p>
 *
 * @param <T> value delivered by the event
 */
public interface EventSource<T> {

    /**
     * Adds one listener during application composition.
     *
     * <p>The same listener is accepted at most once according to equals().
     * This method only registers the listener; it does not invoke or queue it.</p>
     *
     * @return {@code true} when the listener was newly added
     */
    boolean subscribe(Consumer<T> listener);

    /**
     * Adds one filtered listener during application composition.
     *
     * <p>The filter is evaluated synchronously before listener delivery. A value
     * rejected by the filter is not delivered to the listener.</p>
     *
     * @return {@code true} when the filtered listener was newly added
     */
    boolean subscribe(Predicate<T> filter, Consumer<T> listener);
}
