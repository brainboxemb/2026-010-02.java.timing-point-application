package io.github.brainboxemb.eventtiming.timingpoint.domain.node.data;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UniqueTeamRegistrationsTest {

    @Test
    public void duplicateRegistrationsForSameTeamCountOnce() {
        UniqueTeamRegistrations registrations =
                new UniqueTeamRegistrations();
        RegistrationId first =
                new RegistrationId("RT-A-0001");
        RegistrationId second =
                new RegistrationId("RT-X-0001");

        registrations.registered(first);
        registrations.registered(second);

        Map<RegistrationId, TeamId> teams =
                new LinkedHashMap<RegistrationId, TeamId>();
        teams.put(first, new TeamId("0001"));
        teams.put(second, new TeamId("0001"));

        assertEquals(
                1,
                registrations.uniqueTeamCount(teams::get));
    }

    @Test
    public void oneRevokeDoesNotRemoveAnotherActiveRegistration() {
        UniqueTeamRegistrations registrations =
                new UniqueTeamRegistrations();
        RegistrationId registrationId =
                new RegistrationId("RT-A-0001");

        registrations.registered(registrationId);
        registrations.registered(registrationId);
        registrations.revoked(registrationId);

        assertEquals(
                1,
                registrations.activeRegistrationIdCount());
        assertEquals(
                1,
                registrations.uniqueTeamCount(
                        id -> new TeamId("0001")));
    }

    @Test
    public void currentResolverControlsUniqueTeamCount() {
        UniqueTeamRegistrations registrations =
                new UniqueTeamRegistrations();
        RegistrationId first =
                new RegistrationId("RT-R-0001");
        RegistrationId second =
                new RegistrationId("RT-R-0002");
        registrations.registered(first);
        registrations.registered(second);

        Map<RegistrationId, TeamId> teams =
                new LinkedHashMap<RegistrationId, TeamId>();
        teams.put(first, new TeamId("0042"));

        assertEquals(
                1,
                registrations.uniqueTeamCount(teams::get));

        teams.put(second, new TeamId("0043"));
        assertEquals(
                2,
                registrations.uniqueTeamCount(teams::get));

        teams.put(second, new TeamId("0042"));
        assertEquals(
                1,
                registrations.uniqueTeamCount(teams::get));
    }
}
