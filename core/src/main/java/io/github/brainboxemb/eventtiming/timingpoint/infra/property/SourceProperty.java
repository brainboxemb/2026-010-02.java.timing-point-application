package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import java.util.Objects;

/**
 * Passive current value associated with one source object.
 *
 * <p>This class owns no reader, executor, event, retry policy or lifecycle. The
 * owning component decides when a source value is authoritative and calls
 * {@link #update(Object)} explicitly.</p>
 *
 * @param <S> source object type
 * @param <T> property value type
 */
public final class SourceProperty<S, T> {
    private final S source;

    private boolean initialized;
    private T currentValue;

    public SourceProperty(S source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        this.source = source;
    }

    public S source() {
        return source;
    }

    public synchronized boolean initialized() {
        return initialized;
    }

    /**
     * Stores the latest authoritative value.
     *
     * @return true when this is the first value or the effective value changed
     */
    public synchronized boolean update(T value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        boolean changed = !initialized || !Objects.equals(currentValue, value);
        currentValue = value;
        initialized = true;
        return changed;
    }

    public synchronized T currentValue() {
        if (!initialized) {
            throw new IllegalStateException("SourceProperty has no current value");
        }
        return currentValue;
    }
}
