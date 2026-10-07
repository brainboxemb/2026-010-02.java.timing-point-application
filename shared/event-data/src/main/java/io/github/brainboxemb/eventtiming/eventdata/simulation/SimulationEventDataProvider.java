package io.github.brainboxemb.eventtiming.eventdata.simulation;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.EventDataProvider;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public deterministic EventData fixture for engineering simulation.
 *
 * <p>The fixture covers RegistrationIds N0001 through N2000. Every registration
 * owns two synthetic tags with -A and -B suffixes so simulated passages can
 * exercise the normal multi-tag mapping path.</p>
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
        Map<TagId, RegistrationId> registrations =
                new LinkedHashMap<TagId, RegistrationId>();

        for (int number = FIRST_REGISTRATION;
                number <= LAST_REGISTRATION;
                number++) {
            String registrationText =
                    String.format(
                            "N%04d",
                            Integer.valueOf(number));
            RegistrationId registrationId =
                    new RegistrationId(
                            registrationText);

            registrations.put(
                    new TagId(
                            registrationText + "-A"),
                    registrationId);
            registrations.put(
                    new TagId(
                            registrationText + "-B"),
                    registrationId);
        }

        return new EventData(
                registrations);
    }
}
