package io.github.brainboxemb.eventtiming.eventdata;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EventDataTest {

    @Test
    public void oneRegistrationCanOwnMultipleTags() {
        RegistrationId registrationId =
                new RegistrationId("R-123");
        Map<TagId, RegistrationId> registrations =
                new LinkedHashMap<TagId, RegistrationId>();
        registrations.put(
                new TagId("TAG-A"),
                registrationId);
        registrations.put(
                new TagId("TAG-B"),
                registrationId);

        EventData eventData =
                new EventData(
                        registrations);

        assertEquals(
                registrationId,
                eventData.registrationIdFor(
                        new TagId("TAG-A")));
        assertEquals(
                registrationId,
                eventData.registrationIdFor(
                        new TagId("TAG-B")));
        assertEquals(
                2,
                eventData.tagIdsFor(
                        registrationId).size());
        assertNull(
                eventData.registrationIdFor(
                        new TagId("TAG-X")));
    }

    @Test
    public void mapBackedProfileCanResolveTeamInBothDirections() {
        RegistrationId registrationId =
                new RegistrationId("R-123");
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
                        new RegistrationId("R-999")));
    }
}
