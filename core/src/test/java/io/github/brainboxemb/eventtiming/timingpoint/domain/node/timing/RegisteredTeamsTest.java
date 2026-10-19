package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Verifies that unique team counts and display changes are separate from chronological rows. */
public class RegisteredTeamsTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final DefaultTimingDataFactory FACTORY = new DefaultTimingDataFactory();
    private static final TeamId FIRST = new TeamId("0001");
    private static final TeamId SECOND = new TeamId("0002");

    @Test
    public void emitsPlusOnFirstOccurrenceAndMinusOnLastRevoke() {
        ActiveRegistrations registrations = new ActiveRegistrations();
        RegisteredTeams teams = new RegisteredTeams();
        List<RegisteredTeams.Change> changes = new ArrayList<>();
        teams.changes().subscribe(changes::add);

        accept(registrations, teams, record(1L, 1, "A0001", TimingData.RegistrationAction.ADD), FIRST);
        accept(registrations, teams, record(2L, 1, "A0001", TimingData.RegistrationAction.ADD), FIRST);
        accept(registrations, teams, record(3L, 2, "A0001", TimingData.RegistrationAction.ADD), FIRST);
        assertEquals(1, teams.uniqueTeamCount(new LocationId(1)));
        assertEquals(1, teams.uniqueTeamCount(new LocationId(2)));
        assertEquals(2, changes.size());

        accept(registrations, teams, record(4L, 1, "A0001", TimingData.RegistrationAction.REV), FIRST);
        assertEquals(2, changes.size());
        accept(registrations, teams, record(5L, 1, "A0001", TimingData.RegistrationAction.REV), FIRST);
        assertEquals(3, changes.size());
        assertEquals(RegisteredTeams.Change.Action.REMOVED, changes.get(2).action());
        assertEquals(new LocationId(1), changes.get(2).locationId());
        assertEquals(FIRST, changes.get(2).teamId());
        assertEquals(0, teams.uniqueTeamCount(new LocationId(1)));
        assertEquals(1, teams.uniqueTeamCount(new LocationId(2)));
    }

    @Test
    public void multipleRegistrationIdsKeepTeamRegisteredAndBurstContainsUniqueTeams() {
        ActiveRegistrations registrations = new ActiveRegistrations();
        RegisteredTeams teams = new RegisteredTeams();
        accept(registrations, teams, record(1L, 1, "A0001", TimingData.RegistrationAction.ADD), FIRST);
        accept(registrations, teams, record(2L, 1, "R0001", TimingData.RegistrationAction.ADD), FIRST);
        accept(registrations, teams, record(3L, 1, "A0002", TimingData.RegistrationAction.ADD), SECOND);

        List<TeamId> burst = new ArrayList<>();
        assertEquals(2, teams.visit(new LocationId(1), burst::add));
        assertEquals(FIRST, burst.get(0));
        assertEquals(SECOND, burst.get(1));

        accept(registrations, teams, record(4L, 1, "A0001", TimingData.RegistrationAction.REV), FIRST);
        assertEquals(2, teams.uniqueTeamCount(new LocationId(1)));
    }

    @Test
    public void changedAssignmentEmitsOnlyActualMembershipTransitions() {
        ActiveRegistrations registrations = new ActiveRegistrations();
        RegisteredTeams teams = new RegisteredTeams();
        List<RegisteredTeams.Change> changes = new ArrayList<>();
        teams.changes().subscribe(changes::add);
        accept(registrations, teams, record(1L, 1, "R0001", TimingData.RegistrationAction.ADD), null);

        assertEquals(0, changes.size());
        teams.teamAssignmentChanged(new RegistrationId("R0001"), FIRST);
        teams.teamAssignmentChanged(new RegistrationId("R0001"), SECOND);
        teams.teamAssignmentChanged(new RegistrationId("R0001"), null);

        assertEquals(4, changes.size());
        assertEquals(RegisteredTeams.Change.Action.ADDED, changes.get(0).action());
        assertEquals(FIRST, changes.get(0).teamId());
        assertEquals(RegisteredTeams.Change.Action.REMOVED, changes.get(1).action());
        assertEquals(FIRST, changes.get(1).teamId());
        assertEquals(RegisteredTeams.Change.Action.ADDED, changes.get(2).action());
        assertEquals(SECOND, changes.get(2).teamId());
        assertEquals(RegisteredTeams.Change.Action.REMOVED, changes.get(3).action());
        assertEquals(SECOND, changes.get(3).teamId());
    }

    private static void accept(
            ActiveRegistrations registrations, RegisteredTeams teams, TimingData data, TeamId teamId) {

        teams.apply(registrations.apply(data), teamId);
    }

    private static TimingData record(
            long sequence, int location, String id, TimingData.RegistrationAction action) {

        TimingTimestamp time = new TimingTimestamp(Instant.parse("2026-10-13T12:00:00Z"));
        TimingDataFactory.Context context = new TimingDataFactory.Context(
                NODE_ID, sequence, new LocationId(location), time, time);
        return FACTORY.createAutomaticRegistration(context, new RegistrationId(id), action);
    }
}
