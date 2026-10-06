package io.github.brainboxemb.eventtiming.eventdata;

/**
 * Provider SPI for one coherent event-specific EventData profile.
 *
 * <p>The provider contract is deliberately small. Runtime discovery and
 * provider-id selection belong to application Infrastructure; Domain consumers
 * only receive the resulting {@link EventData} instance.</p>
 */
public interface EventDataProvider {

    /** Stable provider/profile identifier used by runtime configuration. */
    String id();

    /** Creates the immutable EventData profile supplied by this provider. */
    EventData createEventData();
}
