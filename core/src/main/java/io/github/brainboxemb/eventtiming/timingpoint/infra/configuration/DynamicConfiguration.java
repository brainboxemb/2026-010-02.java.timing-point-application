package io.github.brainboxemb.eventtiming.timingpoint.infra.configuration;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Writable typed configuration primitive.
 *
 * <p>Normal component dependencies use {@link ReadOnlyConfiguration}. Runtime
 * composition owns concrete instances; Application control code may request
 * validated runtime changes through this interface.</p>
 */
public interface DynamicConfiguration<T> extends ReadOnlyConfiguration<T> {
    static <T> DynamicConfiguration<T> create(
            T startupValue,
            Predicate<T> validator,
            BiPredicate<T, T> runtimeCompatible) {
        return new DefaultDynamicConfiguration<>(
                startupValue,
                validator,
                runtimeCompatible);
    }

    ConfigurationUpdateResult override(T value);

    ConfigurationUpdateResult clearOverride();
}
