package io.github.brainboxemb.eventtiming.timingpoint.application.configuration;

import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Package-local implementation behind the public typed configuration views. */
final class DefaultDynamicConfiguration<T> implements DynamicConfiguration<T> {
    private static final Logger LOG =
            LoggerFactory.getLogger(DefaultDynamicConfiguration.class);

    private final T startupValue;
    private final Predicate<T> validator;
    private final BiPredicate<T, T> runtimeCompatible;
    private final Event<ConfigurationChange<T>> changes = new Event<>();

    private T currentValue;
    private boolean overridden;

    DefaultDynamicConfiguration(
            T startupValue,
            Predicate<T> validator,
            BiPredicate<T, T> runtimeCompatible) {
        if (startupValue == null) {
            throw new IllegalArgumentException("startupValue must not be null");
        }
        if (validator == null) {
            throw new IllegalArgumentException("validator must not be null");
        }
        if (runtimeCompatible == null) {
            throw new IllegalArgumentException(
                    "runtimeCompatible must not be null");
        }
        if (!validator.test(startupValue)) {
            throw new IllegalArgumentException("startupValue is invalid");
        }
        this.startupValue = startupValue;
        this.currentValue = startupValue;
        this.validator = validator;
        this.runtimeCompatible = runtimeCompatible;
    }

    @Override
    public T startupValue() {
        return startupValue;
    }

    @Override
    public synchronized T currentValue() {
        return currentValue;
    }

    @Override
    public synchronized boolean overridden() {
        return overridden;
    }

    @Override
    public EventSource<ConfigurationChange<T>> changes() {
        return changes;
    }

    @Override
    public synchronized ConfigurationUpdateResult override(T value) {
        if (value == null || !validator.test(value)) {
            return ConfigurationUpdateResult.INVALID;
        }
        if (Objects.equals(value, startupValue)) {
            return clearOverride();
        }
        if (!runtimeCompatible.test(startupValue, value)) {
            return ConfigurationUpdateResult.RESTART_REQUIRED;
        }
        if (Objects.equals(value, currentValue)) {
            return ConfigurationUpdateResult.NO_CHANGE;
        }

        T previous = currentValue;
        currentValue = value;
        overridden = true;
        emitChange(new ConfigurationChange<>(
                previous,
                value,
                ConfigurationChange.Source.RUNTIME_OVERRIDE));
        return ConfigurationUpdateResult.APPLIED;
    }

    @Override
    public synchronized ConfigurationUpdateResult clearOverride() {
        if (!overridden) {
            return ConfigurationUpdateResult.NO_CHANGE;
        }

        T previous = currentValue;
        currentValue = startupValue;
        overridden = false;
        emitChange(new ConfigurationChange<>(
                previous,
                startupValue,
                ConfigurationChange.Source.STARTUP_VALUE_RESTORED));
        return ConfigurationUpdateResult.APPLIED;
    }

    private void emitChange(ConfigurationChange<T> change) {
        Event.DeliveryReport report = changes.emit(change);
        if (!report.successful()) {
            LOG.warn(
                    "Configuration change listener failure(s): {}",
                    report.failureCount());
        }
    }
}
