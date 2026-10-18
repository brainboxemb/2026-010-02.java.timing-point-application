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
                        new TagId("TT-A-0001-1")));
        assertEquals(
                normal,
                eventData.registrationIdFor(
                        new TagId("TT-A-0001-2")));
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
                        new TagId("TT-A-9999-1")));
        assertEquals(
                new RegistrationId("A2001"),
                eventData.registrationIdFor(
                        new TagId("TT-A-2001-2")));

        RegistrationId reserve =
                new RegistrationId("R0001");
        assertEquals(
                reserve,
                eventData.registrationIdFor(
                        new TagId("TT-R-0001-1")));
        assertEquals(
                reserve,
                eventData.registrationIdFor(
                        new TagId("TT-R-0001-2")));
        assertEquals(
                2,
                eventData.tagIdsFor(reserve).size());
        assertNull(
                eventData.teamIdFor(reserve));

        assertNull(
                eventData.registrationIdFor(
                        new TagId("TT-A-0000-1")));
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
                "TT-A-0001-1",
                eventData.tagIdsFor(
                        new RegistrationId("A0001"))
                        .get(0)
                        .value());
        assertEquals(
                "TT-A-0001-2",
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
