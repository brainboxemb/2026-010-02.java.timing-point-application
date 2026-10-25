package oss.relay.timing.eventdata;

import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EventDataTest {

    @Test
    public void oneRegistrationCanOwnMultipleTags() {
        RegistrationId registrationId =
                new RegistrationId("R0123");
        Map<TagId, RegistrationId> registrations =
                new LinkedHashMap<TagId, RegistrationId>();
        registrations.put(
                new TagId("R0123A"),
                registrationId);
        registrations.put(
                new TagId("R0123B"),
                registrationId);

        EventData eventData =
                new EventData(
                        registrations);

        assertEquals(
                registrationId,
                eventData.registrationIdFor(
                        new TagId("R0123A")));
        assertEquals(
                registrationId,
                eventData.registrationIdFor(
                        new TagId("R0123B")));
        assertEquals(
                2,
                eventData.tagIdsFor(
                        registrationId).size());
        assertNull(
                eventData.registrationIdFor(
                        new TagId("R0999A")));
    }

    @Test
    public void mapBackedProfileCanResolveTeamInBothDirections() {
        RegistrationId registrationId =
                new RegistrationId("R0123");
        TeamId teamId =
                new TeamId("0042");

        Map<RegistrationId, TeamId> teams =
                new LinkedHashMap<RegistrationId, TeamId>();
        teams.put(
                registrationId,
                teamId);

        EventData eventData =
                new EventData(
                        new LinkedHashMap<TagId, RegistrationId>(),
                        teams);

        assertEquals(
                teamId,
                eventData.teamIdFor(
                        registrationId));
        assertEquals(
                registrationId,
                eventData.registrationIdFor(
                        teamId));
        assertNull(
                eventData.teamIdFor(
                        new RegistrationId("R0999")));
    }
}
