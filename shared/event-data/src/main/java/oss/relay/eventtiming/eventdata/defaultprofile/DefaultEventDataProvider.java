package oss.relay.eventtiming.eventdata.defaultprofile;

import oss.relay.eventtiming.eventdata.EventData;
import oss.relay.eventtiming.eventdata.EventDataProvider;

/**
 * Built-in algorithmic default/reference EventData profile.
 *
 * <p>Normal and reserve TagId-to-RegistrationId relationships follow the
 * public default identity convention without materialising lookup rows.
 * Reserve RegistrationId-to-TeamId assignment remains outside stable EventData.</p>
 */
public final class DefaultEventDataProvider
        implements EventDataProvider {

    public static final String ID = "reference";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EventData createEventData() {
        return new DefaultEventData();
    }
}
