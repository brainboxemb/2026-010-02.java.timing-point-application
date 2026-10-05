package io.github.brainboxemb.eventtiming.timingpoint.infra.configuration;

/** Immutable typed post-fact configuration change. */
public final class ConfigurationChange<T> {
    public enum Source {
        RUNTIME_OVERRIDE,
        STARTUP_VALUE_RESTORED
    }

    private final T previousValue;
    private final T currentValue;
    private final Source source;

    ConfigurationChange(
            T previousValue,
            T currentValue,
            Source source) {
        this.previousValue = previousValue;
        this.currentValue = currentValue;
        this.source = source;
    }

    public T previousValue() {
        return previousValue;
    }

    public T currentValue() {
        return currentValue;
    }

    public Source source() {
        return source;
    }
}
