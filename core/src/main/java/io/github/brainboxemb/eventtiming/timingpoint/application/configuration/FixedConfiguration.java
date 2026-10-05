package io.github.brainboxemb.eventtiming.timingpoint.application.configuration;

import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/** Immutable read-only configuration implementation used for fixed values. */
final class FixedConfiguration<T> implements ReadOnlyConfiguration<T> {
    private final T value;
    private final Event<ConfigurationChange<T>> changes = new Event<>();

    FixedConfiguration(T value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        this.value = value;
    }

    @Override
    public T startupValue() {
        return value;
    }

    @Override
    public T currentValue() {
        return value;
    }

    @Override
    public boolean overridden() {
        return false;
    }

    @Override
    public EventSource<ConfigurationChange<T>> changes() {
        return changes;
    }
}
