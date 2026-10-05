package io.github.brainboxemb.eventtiming.timingpoint.application.configuration;

/**
 * Writable application-control view of one typed configuration value.
 *
 * <p>Normal component dependencies use {@link ReadOnlyConfiguration}; this
 * interface is retained by the application-control side of the configuration
 * system.</p>
 */
public interface DynamicConfiguration<T> extends ReadOnlyConfiguration<T> {
    ConfigurationUpdateResult override(T value);

    ConfigurationUpdateResult clearOverride();
}
