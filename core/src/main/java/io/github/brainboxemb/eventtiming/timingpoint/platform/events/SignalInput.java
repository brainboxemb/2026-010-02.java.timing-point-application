package io.github.brainboxemb.eventtiming.timingpoint.platform.events;

import java.util.function.BiConsumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Strongly typed composition-time payload-less signal input of a component. */
public final class SignalInput<C> {
    private final BiConsumer<C, SignalSource> binder;

    private SignalInput(BiConsumer<C, SignalSource> binder) {

        failIfInvalidArgument(binder != null, "binder must not be null");
        this.binder = binder;
    }

    /** Creates one signal input from component-owned binding behaviour. */
    public static <C> SignalInput<C> of(BiConsumer<C, SignalSource> binder) {

        return new SignalInput<C>(binder);
    }

    /** Binds one source to this input of the supplied component. */
    public void bind(C component, SignalSource source) {

        failIfInvalidArgument(component != null, "component must not be null");
        failIfInvalidArgument(source != null, "source must not be null");
        binder.accept(component, source);
    }
}
