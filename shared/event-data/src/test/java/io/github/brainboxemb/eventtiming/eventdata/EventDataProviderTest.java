package io.github.brainboxemb.eventtiming.eventdata;

import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventDataProvider;
import io.github.brainboxemb.eventtiming.eventdata.simulation.SimulationEventDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EventDataProviderTest {

    @Test
    public void referenceProviderHasStableIdAndCreatesEmptyProfile() {
        EventDataProvider provider =
                new DefaultEventDataProvider();

        assertEquals(
                "reference",
                provider.id());
        assertEquals(
                DefaultEventDataProvider.ID,
                provider.id());
        assertTrue(
                provider.createEventData().isEmpty());
    }

    @Test
    public void simulationProviderCreatesTwoTagsPerRegistration() {
        EventDataProvider provider =
                new SimulationEventDataProvider();
        EventData eventData =
                provider.createEventData();

        assertEquals(
                "simulation",
                provider.id());
        assertEquals(
                2,
                eventData.tagIdsFor(
                        new RegistrationId("N0001"))
                        .size());
        assertEquals(
                "N0001-A",
                eventData.tagIdsFor(
                        new RegistrationId("N0001"))
                        .get(0)
                        .value());
        assertEquals(
                "N0001-B",
                eventData.tagIdsFor(
                        new RegistrationId("N0001"))
                        .get(1)
                        .value());
        assertEquals(
                2,
                eventData.tagIdsFor(
                        new RegistrationId("N2000"))
                        .size());
        assertTrue(
                eventData.tagIdsFor(
                        new RegistrationId("N2001"))
                        .isEmpty());
    }
}
