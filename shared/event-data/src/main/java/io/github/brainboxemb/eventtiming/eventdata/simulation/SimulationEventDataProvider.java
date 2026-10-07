package io.github.brainboxemb.eventtiming.eventdata.simulation;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.EventDataProvider;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;

/**
 * Public deterministic EventData fixture for engineering simulation.
 *
 * <p>The fixture uses the default/reference identifier grammar for numbers
 * 0001 through 2000. No synthetic N0001-A/B identity grammar is introduced.</p>
 */
public final class SimulationEventDataProvider
        implements EventDataProvider {

    public static final String ID = "simulation";
    private static final int FIRST_REGISTRATION = 1;
    private static final int LAST_REGISTRATION = 2000;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EventData createEventData() {
        return DefaultEventData.normalOnly(
                FIRST_REGISTRATION,
                LAST_REGISTRATION);
    }
}
