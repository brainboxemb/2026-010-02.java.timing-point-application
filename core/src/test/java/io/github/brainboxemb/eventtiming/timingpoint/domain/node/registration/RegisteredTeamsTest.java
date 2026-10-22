package io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Verifies unique-team state for one active WP and silent historical reconstruction. */
public class RegisteredTeamsTest {
    private static final TeamId FIRST = new TeamId("0001");
    private static final TeamId SECOND = new TeamId("0002");

    @Test
    public void emitsPlusOnFirstLiveOccurrenceAndMinusOnLastRevoke() {
        ActiveRegistrations registrations = activeRegistrations();
        RegisteredTeams teams = liveTeams();
        List<RegisteredTeams.Change> changes = new ArrayList<>();
        teams.CHANGED.subscribe(changes::add);

        accept(registrations, teams, record(1L, "A0001", TimingRecord.Action.ADD), FIRST);
        accept(registrations, teams, record(2L, "A0001", TimingRecord.Action.ADD), FIRST);
        assertEquals(1, teams.uniqueTeamCount());
        assertEquals(1, changes.size());

        accept(registrations, teams, record(3L, "A0001", TimingRecord.Action.REV), FIRST);
        assertEquals(1, changes.size());
        accept(registrations, teams, record(4L, "A0001", TimingRecord.Action.REV), FIRST);
        assertEquals(2, changes.size());
        assertEquals(RegisteredTeams.Change.Action.REMOVED, changes.get(1).action());
        assertEquals(FIRST, changes.get(1).teamId());
        assertEquals(0, teams.uniqueTeamCount());
    }

    @Test
    public void historicalRebuildProducesSetWithoutIncrementalEvents() {
        ActiveRegistrations registrations = activeRegistrations();
        RegisteredTeams teams = new RegisteredTeams();
        List<RegisteredTeams.Change> changes = new ArrayList<>();
        teams.CHANGED.subscribe(changes::add);
        teams.beginRebuild();

        accept(registrations, teams, record(1L, "A0001", TimingRecord.Action.ADD), FIRST);
        accept(registrations, teams, record(2L, "A0002", TimingRecord.Action.ADD), SECOND);

        assertEquals(2, teams.uniqueTeamCount());
        assertEquals(0, changes.size());

        teams.completeRebuild();
        accept(registrations, teams, record(3L, "R0001", TimingRecord.Action.ADD), FIRST);
        assertEquals(0, changes.size());
        accept(registrations, teams, record(4L, "A0003", TimingRecord.Action.ADD), new TeamId("0003"));
        assertEquals(1, changes.size());
    }

    @Test
    public void multipleRegistrationIdsKeepTeamRegisteredAndBurstContainsUniqueTeams() {
        ActiveRegistrations registrations = activeRegistrations();
        RegisteredTeams teams = liveTeams();
        accept(registrations, teams, record(1L, "A0001", TimingRecord.Action.ADD), FIRST);
        accept(registrations, teams, record(2L, "R0001", TimingRecord.Action.ADD), FIRST);
        accept(registrations, teams, record(3L, "A0002", TimingRecord.Action.ADD), SECOND);

        List<TeamId> burst = new ArrayList<>();
        assertEquals(2, teams.visit(burst::add));
        org.junit.Assert.assertTrue(burst.contains(FIRST));
        org.junit.Assert.assertTrue(burst.contains(SECOND));

        accept(registrations, teams, record(4L, "A0001", TimingRecord.Action.REV), FIRST);
        assertEquals(2, teams.uniqueTeamCount());
    }

    @Test
    public void changedAssignmentMovesAllExistingOccurrences() {
        ActiveRegistrations registrations = activeRegistrations();
        RegisteredTeams teams = liveTeams();
        List<RegisteredTeams.Change> changes = new ArrayList<>();
        teams.CHANGED.subscribe(changes::add);
        accept(registrations, teams, record(1L, "R0001", TimingRecord.Action.ADD), null);
        accept(registrations, teams, record(2L, "R0001", TimingRecord.Action.ADD), null);

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

    private static ActiveRegistrations activeRegistrations() {
        ActiveRegistrations registrations = new ActiveRegistrations();
        registrations.activate(new LocationId(1));
        return registrations;
    }

    private static RegisteredTeams liveTeams() {
        RegisteredTeams teams = new RegisteredTeams();
        teams.beginRebuild();
        teams.completeRebuild();
        return teams;
    }

    private static void accept(
            ActiveRegistrations registrations,
            RegisteredTeams teams,
            TimingRecord record,
            TeamId teamId) {
        teams.apply(registrations.apply(record), teamId);
    }

    private static TimingRecord record(
            long sequence,
            String id,
            TimingRecord.Action action) {
        long time = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli();
        return TimingRecord.createAutomaticRegistration(
                'A',
                sequence,
                1,
                time,
                time,
                new RegistrationId(id).packedValue(),
                action,
                null,
                null);
    }
}
