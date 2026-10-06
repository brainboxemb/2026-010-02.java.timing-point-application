package io.github.brainboxemb.eventtiming.timingpoint.infra.extension;

import io.github.brainboxemb.eventtiming.eventdata.EventDataProvider;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataProvider;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup registry for typed SI-01 extension providers.
 *
 * <p>The first implementation slice contains only EventData and TimingData
 * provider families. Built-in reference providers are always registered first;
 * additional implementations may be discovered through standard Java
 * {@link ServiceLoader} metadata from a caller-supplied ClassLoader.</p>
 *
 * <p>This class is not a generic plugin API. It performs typed discovery,
 * duplicate-id validation and lookup only. It does not own ClassLoader
 * lifecycle, filesystem scanning, hot reload or application behaviour.</p>
 */
public final class ExtensionRegistry {
    private static final Logger LOG =
            LoggerFactory.getLogger(ExtensionRegistry.class);

    private final Map<String, EventDataProvider> eventDataProviders;
    private final Map<String, TimingDataProvider> timingDataProviders;

    private ExtensionRegistry(
            Map<String, EventDataProvider> eventDataProviders,
            Map<String, TimingDataProvider> timingDataProviders) {
        this.eventDataProviders =
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, EventDataProvider>(
                                eventDataProviders));
        this.timingDataProviders =
                Collections.unmodifiableMap(
                        new LinkedHashMap<String, TimingDataProvider>(
                                timingDataProviders));
    }

    /**
     * Creates a registry containing only the public built-in providers.
     */
    public static ExtensionRegistry builtIns() {
        Map<String, EventDataProvider> eventData =
                new LinkedHashMap<String, EventDataProvider>();
        Map<String, TimingDataProvider> timingData =
                new LinkedHashMap<String, TimingDataProvider>();

        registerEventDataProvider(
                eventData,
                new DefaultEventDataProvider());
        registerTimingDataProvider(
                timingData,
                new DefaultTimingDataProvider());

        return new ExtensionRegistry(
                eventData,
                timingData);
    }

    /**
     * Creates a registry from built-ins plus providers visible to one loader.
     *
     * <p>The caller owns the supplied ClassLoader. In V04 verification this is
     * a dedicated URLClassLoader over a synthetic external provider JAR. A
     * deployment-specific JAR directory/layout is deliberately not owned here.</p>
     */
    public static ExtensionRegistry discover(
            ClassLoader extensionClassLoader) {
        if (extensionClassLoader == null) {
            throw new IllegalArgumentException(
                    "extensionClassLoader must not be null");
        }

        Map<String, EventDataProvider> eventData =
                new LinkedHashMap<String, EventDataProvider>();
        Map<String, TimingDataProvider> timingData =
                new LinkedHashMap<String, TimingDataProvider>();

        registerEventDataProvider(
                eventData,
                new DefaultEventDataProvider());
        registerTimingDataProvider(
                timingData,
                new DefaultTimingDataProvider());

        discoverEventDataProviders(
                eventData,
                extensionClassLoader);
        discoverTimingDataProviders(
                timingData,
                extensionClassLoader);

        return new ExtensionRegistry(
                eventData,
                timingData);
    }

    /**
     * Returns the configured EventData provider or fails deterministically.
     */
    public EventDataProvider eventDataProvider(
            String providerId) {
        String id =
                requireLookupId(
                        providerId,
                        "EventDataProvider");
        EventDataProvider provider =
                eventDataProviders.get(id);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Unknown EventDataProvider id "
                            + id);
        }
        return provider;
    }

    /**
     * Returns the configured TimingData provider or fails deterministically.
     */
    public TimingDataProvider timingDataProvider(
            String providerId) {
        String id =
                requireLookupId(
                        providerId,
                        "TimingDataProvider");
        TimingDataProvider provider =
                timingDataProviders.get(id);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Unknown TimingDataProvider id "
                            + id);
        }
        return provider;
    }

    private static void discoverEventDataProviders(
            Map<String, EventDataProvider> providers,
            ClassLoader classLoader) {
        try {
            for (EventDataProvider provider
                    : ServiceLoader.load(
                            EventDataProvider.class,
                            classLoader)) {
                registerEventDataProvider(
                        providers,
                        provider);
            }
        } catch (ServiceConfigurationError error) {
            throw new IllegalStateException(
                    "Could not discover EventDataProvider implementations",
                    error);
        }
    }

    private static void discoverTimingDataProviders(
            Map<String, TimingDataProvider> providers,
            ClassLoader classLoader) {
        try {
            for (TimingDataProvider provider
                    : ServiceLoader.load(
                            TimingDataProvider.class,
                            classLoader)) {
                registerTimingDataProvider(
                        providers,
                        provider);
            }
        } catch (ServiceConfigurationError error) {
            throw new IllegalStateException(
                    "Could not discover TimingDataProvider implementations",
                    error);
        }
    }

    private static void registerEventDataProvider(
            Map<String, EventDataProvider> providers,
            EventDataProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException(
                    "EventDataProvider must not be null");
        }

        String id =
                requireProviderId(
                        provider.id(),
                        "EventDataProvider",
                        provider.getClass());

        EventDataProvider existing =
                providers.put(
                        id,
                        provider);
        if (existing != null) {
            providers.put(
                    id,
                    existing);
            throw duplicateProvider(
                    "EventDataProvider",
                    id,
                    existing.getClass(),
                    provider.getClass());
        }

        LOG.debug(
                "Registered EventDataProvider id={} implementation={}",
                id,
                provider.getClass().getName());
    }

    private static void registerTimingDataProvider(
            Map<String, TimingDataProvider> providers,
            TimingDataProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException(
                    "TimingDataProvider must not be null");
        }

        String id =
                requireProviderId(
                        provider.id(),
                        "TimingDataProvider",
                        provider.getClass());

        TimingDataProvider existing =
                providers.put(
                        id,
                        provider);
        if (existing != null) {
            providers.put(
                    id,
                    existing);
            throw duplicateProvider(
                    "TimingDataProvider",
                    id,
                    existing.getClass(),
                    provider.getClass());
        }

        LOG.debug(
                "Registered TimingDataProvider id={} implementation={}",
                id,
                provider.getClass().getName());
    }

    private static String requireProviderId(
            String providerId,
            String family,
            Class<?> implementation) {
        if (providerId == null
                || providerId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    family
                            + " implementation "
                            + implementation.getName()
                            + " returned a blank provider id");
        }
        return providerId.trim();
    }

    private static String requireLookupId(
            String providerId,
            String family) {
        if (providerId == null
                || providerId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    family
                            + " id must not be blank");
        }
        return providerId.trim();
    }

    private static IllegalArgumentException duplicateProvider(
            String family,
            String id,
            Class<?> existing,
            Class<?> duplicate) {
        return new IllegalArgumentException(
                "Duplicate "
                        + family
                        + " id "
                        + id
                        + ": "
                        + existing.getName()
                        + " and "
                        + duplicate.getName());
    }
}
