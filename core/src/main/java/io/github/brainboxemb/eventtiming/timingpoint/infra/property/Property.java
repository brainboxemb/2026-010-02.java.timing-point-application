package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * One named value observed by a PropertyContainer.
 *
 * @param <T> property value type
 */
public final class Property<T> {
    public final String name;

    private final Supplier<T> source;
    private T lastPublishedValue;

    Property(String name, Supplier<T> source) {
        this.name = name;
        this.source = source;
        this.lastPublishedValue = source.get();
    }

    /** Returns the owner's current value. */
    public T value() {
        return source.get();
    }

    /** Records the current value and reports whether it changed since the last update. */
    boolean update() {
        T currentValue = source.get();
        if (Objects.equals(lastPublishedValue, currentValue)) {
            return false;
        }

        lastPublishedValue = currentValue;
        return true;
    }
}
