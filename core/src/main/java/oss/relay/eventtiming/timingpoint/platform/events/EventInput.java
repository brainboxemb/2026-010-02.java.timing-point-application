package oss.relay.eventtiming.timingpoint.platform.events;

import java.util.function.BiConsumer;

/**
 * Strongly typed composition-time event input of a component.
 *
 * <p>The owning component declares named EventInput constants that know how an
 * incoming EventSource is connected internally. A composition owner selects the
 * semantic input and supplies the source without learning which child object or
 * handler consumes the event.</p>
 *
 * @param <C> receiving component type
 * @param <T> event value type
 */
public final class EventInput<C, T> {
    private final BiConsumer<C, EventSource<T>> binder;

    private EventInput(BiConsumer<C, EventSource<T>> binder) {
        if (binder == null) {
            throw new IllegalArgumentException("binder must not be null");
        }
        this.binder = binder;
    }

    /** Creates one typed event input from component-owned binding behaviour. */
    public static <C, T> EventInput<C, T> of(
            BiConsumer<C, EventSource<T>> binder) {

        return new EventInput<C, T>(binder);
    }

    /** Binds one source to this input of the supplied component. */
    public void bind(C component, EventSource<T> source) {
        if (component == null) {
            throw new IllegalArgumentException("component must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        binder.accept(component, source);
    }
}
