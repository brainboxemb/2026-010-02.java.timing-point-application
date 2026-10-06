package io.github.brainboxemb.eventtiming.timingpoint.platform.events;

import java.util.function.Consumer;

/**
 * Subscription-only view of a local typed event.
 *
 * <p>The owner keeps the mutable {@link Event} so only that owner can decide
 * when a fact is emitted. Runtime composition uses this view to connect one or
 * more listeners before application activation.</p>
 *
 * <p>Subscription is a <strong>composition-time</strong> operation. The SI-01
 * runtime graph is fixed before components are activated; runtime rewiring is
 * deliberately not part of this abstraction. EventSource therefore exposes no
 * unsubscribe operation.</p>
 *
 * @param <T> immutable/read-only value delivered by the event
 */
public interface EventSource<T> {

    /**
     * Adds one listener during application composition.
     *
     * <p>The same listener is accepted at most once according to equals().
     * Callers must finish wiring before application activation.</p>
     *
     * @return {@code true} when the listener was newly added
     */
    boolean subscribe(Consumer<T> listener);
}
