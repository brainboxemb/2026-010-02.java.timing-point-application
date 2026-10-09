package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Passive current value calculated from other current state.
 *
 * <p>The supplied derivation may read one or more SourceProperties. This class
 * performs no scheduling: the owning component explicitly calls
 * {@link #recalculate()} when its control behaviour decides derived state must
 * be refreshed.</p>
 *
 * @param <T> derived value type
 */
public final class DerivedProperty<T> {
    private final Supplier<T> derivation;

    private boolean initialized;
    private T currentValue;

    public DerivedProperty(Supplier<T> derivation) {
        if (derivation == null) {
            throw new IllegalArgumentException("derivation must not be null");
        }
        this.derivation = derivation;
    }

    public synchronized boolean initialized() {
        return initialized;
    }

    /**
     * Recalculates from the current dependencies.
     *
     * @return true when this is the first value or the effective value changed
     */
    public synchronized boolean recalculate() {
        T value = derivation.get();
        if (value == null) {
            throw new IllegalStateException("DerivedProperty derivation returned null");
        }

        boolean changed = !initialized || !Objects.equals(currentValue, value);
        currentValue = value;
        initialized = true;
        return changed;
    }

    public synchronized T currentValue() {
        if (!initialized) {
            throw new IllegalStateException("DerivedProperty has no current value");
        }
        return currentValue;
    }
}
