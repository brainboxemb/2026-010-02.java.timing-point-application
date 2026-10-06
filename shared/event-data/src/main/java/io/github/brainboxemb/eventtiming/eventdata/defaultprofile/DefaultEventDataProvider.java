package io.github.brainboxemb.eventtiming.eventdata.defaultprofile;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.EventDataProvider;

/**
 * Built-in empty EventData profile used until deployment/provider selection
 * supplies an event-specific profile.
 *
 * <p>This provider is intentionally deterministic and contains no deployment
 * mappings. External event-specific providers can supply real profile data
 * through the same SPI.</p>
 */
public final class DefaultEventDataProvider
        implements EventDataProvider {

    public static final String ID = "default";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EventData createEventData() {
        return EventData.empty();
    }
}
