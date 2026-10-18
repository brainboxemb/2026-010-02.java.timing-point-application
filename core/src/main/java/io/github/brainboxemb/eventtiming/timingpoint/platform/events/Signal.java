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
        return event.subscribe(new SignalListener(listener));
    }

    /** Delivers this signal synchronously to current listeners. */
    public Event.DeliveryReport fire() {

        return event.emit(FIRED);
    }

    /** Adapts Runnable identity to Event listener identity. */
    private static final class SignalListener implements Consumer<Object> {
        private final Runnable listener;

        private SignalListener(Runnable listener) {

            this.listener = listener;
        }

        @Override
        public void accept(Object ignored) {

            listener.run();
        }

        @Override
        public boolean equals(Object other) {

            return other instanceof SignalListener
                    && listener.equals(((SignalListener) other).listener);
        }

        @Override
        public int hashCode() {

            return listener.hashCode();
        }
    }
}
