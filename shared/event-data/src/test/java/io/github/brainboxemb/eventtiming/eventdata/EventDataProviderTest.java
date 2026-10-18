package io.github.brainboxemb.eventtiming.eventdata;

import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventDataProvider;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EventDataProviderTest {

    @Test
    public void referenceProviderUsesDefaultIdentityConvention() {
        EventDataProvider provider =
                new DefaultEventDataProvider();
        EventData eventData =
                provider.createEventData();

        assertEquals(
                "reference",
                provider.id());
        assertFalse(
                eventData.isEmpty());

        RegistrationId normal =
                new RegistrationId("A0001");
        assertEquals(
                normal,
                eventData.registrationIdFor(
                        new TagId("A0001A")));
        assertEquals(
                normal,
                eventData.registrationIdFor(
                        new TagId("A0001B")));
        assertEquals(
                2,
                eventData.tagIdsFor(normal).size());
        assertEquals(
                "0001",
                eventData.teamIdFor(normal).value());
        assertEquals(
                normal,
                eventData.registrationIdFor(
                        new TeamId("0001")));

        RegistrationId upperNormal =
                new RegistrationId("A9999");
        assertEquals(
                upperNormal,
                eventData.registrationIdFor(
                        new TagId("A9999A")));
        assertEquals(
                new RegistrationId("A2001"),
                eventData.registrationIdFor(
                        new TagId("A2001B")));

        RegistrationId reserve =
                new RegistrationId("R0001");
        assertEquals(
                reserve,
                eventData.registrationIdFor(
                        new TagId("R0001A")));
        assertEquals(
                reserve,
                eventData.registrationIdFor(
                        new TagId("R0001B")));
        assertEquals(
                2,
                eventData.tagIdsFor(reserve).size());
        assertNull(
                eventData.teamIdFor(reserve));

        assertNull(
                eventData.registrationIdFor(
                        new TagId("A0000A")));
        assertTrue(
                eventData.tagIdsFor(
                        new RegistrationId("A0000"))
                        .isEmpty());
        assertNull(
                eventData.registrationIdFor(
                        new TeamId("0000")));
    }

    @Test
    public void boundedDefaultEventDataUsesReferenceGrammarWithoutAnotherProvider() {
        EventData eventData =
                DefaultEventData.normalOnly(1, 2000);
        assertEquals(
                2,
                eventData.tagIdsFor(
                        new RegistrationId("A0001"))
                        .size());
        assertEquals(
                "A0001A",
                eventData.tagIdsFor(
                        new RegistrationId("A0001"))
                        .get(0)
                        .value());
        assertEquals(
                "A0001B",
                eventData.tagIdsFor(
                        new RegistrationId("A0001"))
                        .get(1)
                        .value());
        assertEquals(
                2,
                eventData.tagIdsFor(
                        new RegistrationId("A2000"))
                        .size());
        assertTrue(
                eventData.tagIdsFor(
                        new RegistrationId("A2001"))
                        .isEmpty());
    }
}
