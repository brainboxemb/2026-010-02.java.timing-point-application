package oss.relay.timing.timingpoint.domain.node.racedata;

import oss.relay.timing.eventdata.EventData;
import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RaceDataStateTest {

    @Test
    public void stageStartMessageRevisionAdvancesOnceForRetainedChanges() {
        RaceDataState state = new RaceDataState(EventData.empty());
        RegistrationId first = new RegistrationId("R0042");
        RegistrationId second = new RegistrationId("R0043");

        boolean changed = false;
        changed |= state.stageStartSet(first, 12_34_56_78L);
        changed |= state.stageStartSet(second, 12_35_00_00L);
        state.stageStartChangesApplied(changed);

        assertEquals(1L, state.stageStartRevision());
        assertEquals(Long.valueOf(12_34_56_78L), state.stageStartFor(first));
        assertEquals(Long.valueOf(12_35_00_00L), state.stageStartFor(second));

        changed = state.stageStartSet(first, 12_34_56_78L);
        state.stageStartChangesApplied(changed);
        assertEquals(1L, state.stageStartRevision());

        changed = state.stageStartDeleted(first);
        state.stageStartChangesApplied(changed);
        assertNull(state.stageStartFor(first));
        assertEquals(2L, state.stageStartRevision());
    }

    @Test
    public void technicalVisitorsExposeOnlyLiveCurrentState() {
        RegistrationId fixed = new RegistrationId("F0001");
        EventData eventData =
                new EventData(
                        Collections.emptyMap(),
                        Collections.singletonMap(fixed, new TeamId("0001")));
        RaceDataState state = new RaceDataState(eventData);
        RegistrationId first = new RegistrationId("R0042");
        RegistrationId second = new RegistrationId("R0043");

        state.assignTeam(first, new TeamId("0042"));
        state.stageStartSet(second, 12_34_56_78L);
        state.stageStartChangesApplied(true);

        List<String> mappings = new ArrayList<String>();
        List<String> stageStarts = new ArrayList<String>();
        assertEquals(
                1,
                state.visitLiveAssignments(
                        (registrationId, teamId) ->
                                mappings.add(
                                        registrationId.value()
                                                + "="
                                                + teamId.value())));
        assertEquals(
                1,
                state.visitStageStarts(
                        (registrationId, stageStart) ->
                                stageStarts.add(
                                        registrationId.value()
                                                + "="
                                                + stageStart)));

        assertEquals(Collections.singletonList("R0042=0042"), mappings);
        assertEquals(new TeamId("0001"), state.teamIdFor(fixed));
        assertEquals(Collections.singletonList("R0043=12345678"), stageStarts);
    }

    @Test
    public void liveAssignmentOwnerCanBeResolvedWithoutReverseIndexState() {
        RaceDataState state = new RaceDataState(EventData.empty());
        RegistrationId registrationId = new RegistrationId("R0042");
        TeamId teamId = new TeamId("0042");

        assertNull(state.liveRegistrationIdFor(teamId));

        state.assignTeam(registrationId, teamId);
        assertEquals(registrationId, state.liveRegistrationIdFor(teamId));

        state.removeTeam(registrationId);
        assertNull(state.liveRegistrationIdFor(teamId));
    }

    @Test
    public void engineeringStageStartReplacesOneCompactUtcValue() {
        RaceDataState state = new RaceDataState(EventData.empty());
        RegistrationId registrationId = new RegistrationId("R0042");

        state.setStageStart(registrationId, 12_34_56_78L);
        assertEquals(
                Long.valueOf(12_34_56_78L),
                state.stageStartFor(registrationId));

        state.setStageStart(registrationId, 2026_10_23_12_34_57_12L);
        assertEquals(
                Long.valueOf(2026_10_23_12_34_57_12L),
                state.stageStartFor(registrationId));
    }
}
