package io.github.brainboxemb.eventtiming.timingpoint.platform.events;

import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Small synchronous local signal without a payload.
 *
 * <p>Signal has the same synchronous delivery semantics as Event, but listeners receive
 * no value. Use it when only the fact that something changed matters.</p>
 */
public final class Signal implements SignalSource {
    private static final Object FIRED = new Object();

    private final Event<Object> event = new Event<Object>();

    /** Subscribes one payload-less listener. */
    @Override
    public boolean subscribe(Runnable listener) {

        failIfInvalidArgument(listener != null, "listener must not be null");

        Consumer<Object> callback = ignored -> listener.run();
        return event.subscribe(callback);
    }

    /** Delivers this signal synchronously to current listeners. */
    public Event.DeliveryReport fire() {

        return event.emit(FIRED);
    }
}
