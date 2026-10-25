package oss.relay.eventtiming.timingpoint.infra.property;

import java.util.Objects;

/**
 * One typed current value owned by a PropertyContainer.
 *
 * @param <T> property value type
 */
public final class Property<T> {
    final PropertyContainer owner;
    final PropertyKey<T> key;
    public final String name;

    private volatile T value;
    private T lastPublishedValue;

    Property(
            PropertyContainer owner,
            PropertyKey<T> key,
            T initialValue) {

        this.owner = owner;
        this.key = key;
        this.name = key.name;
        this.value = initialValue;
        this.lastPublishedValue = initialValue;
    }

    /** Returns the current value. */
    public T get() {
        return value;
    }

    /** Replaces the current value. Publication is grouped by PropertyContainer.update(). */
    public void set(T value) {
        this.value = value;
    }

    /** Accepts the current value as the comparison baseline without publishing a change. */
    void sync() {
        lastPublishedValue = value;
    }

    /** Records the current value and reports whether it changed since the last update. */
    boolean update() {
        T currentValue = value;
        if (Objects.equals(lastPublishedValue, currentValue)) {
            return false;
        }

        lastPublishedValue = currentValue;
        return true;
    }
}
