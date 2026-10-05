package io.github.brainboxemb.eventtiming.timingpoint.application.configuration;

import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/**
 * Read-only view of one typed running configuration value.
 *
 * @param <T> immutable configuration value type
 */
public interface ReadOnlyConfiguration<T> {
    /** Returns an immutable read-only configuration that never changes. */
    static <T> ReadOnlyConfiguration<T> fixed(T value) {
        return new FixedConfiguration<>(value);
    }

    /** Effective value resolved when the application was composed. */
    T startupValue();

    /** Current authoritative value used by the running application. */
    T currentValue();

    /** Whether currentValue differs because of a runtime override. */
    boolean overridden();

    /** Post-fact notifications after the authoritative current value changes. */
    EventSource<ConfigurationChange<T>> changes();
}
