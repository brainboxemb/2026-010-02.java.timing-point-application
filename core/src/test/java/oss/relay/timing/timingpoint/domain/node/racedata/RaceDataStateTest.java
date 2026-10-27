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
import static org.junit.Assert.fail;

public class RaceDataStateTest {

    @Test
    public void raceDataStageStartSetAndDeleteUseRegistrationIdentity() {
        RaceDataState state = new RaceDataState(EventData.empty());
        RaceDataUpdate update = new RaceDataUpdate(16);
        RegistrationId registrationId = new RegistrationId("R0042");

        update.setStageStart(registrationId, 12_34_56_78L);
        state.applyChanges(update);
        assertEquals(1L, state.stageStartRevision());
        assertEquals(Long.valueOf(12_34_56_78L), state.stageStartFor(registrationId));

        update.clear();
        update.setStageStart(registrationId, 12_34_56_78L);
        state.applyChanges(update);
        assertEquals(1L, state.stageStartRevision());

        update.clear();
        update.deleteStageStart(registrationId);
        state.applyChanges(update);
        assertNull(state.stageStartFor(registrationId));
        assertEquals(2L, state.stageStartRevision());
    }

    @Test
    public void technicalVisitorsExposeOnlyLiveRaceDataState() {
        RegistrationId fixed = new RegistrationId("F0001");
        EventData eventData = new EventData(
                Collections.emptyMap(),
                Collections.singletonMap(fixed, new TeamId("0001")));
        RaceDataState state = new RaceDataState(eventData);
        RaceDataUpdate update = new RaceDataUpdate(16);
        RegistrationId first = new RegistrationId("R0042");
        RegistrationId second = new RegistrationId("R0043");

        update.setTeamAssignment(first, new TeamId("0042"));
        update.setStageStart(second, 12_34_56_78L);
        state.applyChanges(update);

        List<String> mappings = new ArrayList<String>();
        List<String> stageStarts = new ArrayList<String>();
        assertEquals(1, state.visitLiveAssignments(
                (registrationId, teamId) ->
                        mappings.add(registrationId.value() + "=" + teamId.value())));
        assertEquals(1, state.visitStageStarts(
                (registrationId, stageStart) ->
                        stageStarts.add(registrationId.value() + "=" + stageStart)));

        assertEquals(Collections.singletonList("R0042=0042"), mappings);
        assertEquals(new TeamId("0001"), state.teamIdFor(fixed));
        assertEquals(Collections.singletonList("R0043=12345678"), stageStarts);
    }

    @Test
    public void invalidAssignmentTransactionDoesNotApplyStageStart() {
        RaceDataState state = new RaceDataState(EventData.empty());
        RaceDataUpdate update = new RaceDataUpdate(16);
        RegistrationId first = new RegistrationId("R0042");
        RegistrationId second = new RegistrationId("R0043");
        TeamId teamId = new TeamId("0042");

        update.setTeamAssignment(first, teamId);
        state.applyChanges(update);

        update.clear();
        update.setStageStart(second, 12_34_56_78L);
        update.setTeamAssignment(second, teamId);
        try {
            state.applyChanges(update);
            fail("duplicate TeamId must reject the complete transaction");
        } catch (IllegalArgumentException expected) {
            // Validation completed before mutation.
        }

        assertNull(state.stageStartFor(second));
        assertNull(state.teamIdFor(second));
        assertEquals(teamId, state.teamIdFor(first));
    }

    @Test
    public void assignmentSwapValidatesAgainstFinalTransaction() {
        RaceDataState state = new RaceDataState(EventData.empty());
        RaceDataUpdate update = new RaceDataUpdate(16);
        RegistrationId first = new RegistrationId("R0042");
        RegistrationId second = new RegistrationId("R0043");
        TeamId firstTeam = new TeamId("0042");
        TeamId secondTeam = new TeamId("0043");

        update.setTeamAssignment(first, firstTeam);
        update.setTeamAssignment(second, secondTeam);
        state.applyChanges(update);

        update.clear();
        update.setTeamAssignment(first, secondTeam);
        update.setTeamAssignment(second, firstTeam);
        state.applyChanges(update);

        assertEquals(secondTeam, state.teamIdFor(first));
        assertEquals(firstTeam, state.teamIdFor(second));
    }

    @Test
    public void deleteWinsOverSetInReusableBuffer() {
        RaceDataUpdate update = new RaceDataUpdate(16);
        RegistrationId registrationId = new RegistrationId("R0042");

        update.setStageStart(registrationId, 12_34_56_78L);
        update.deleteStageStart(registrationId);
        update.setStageStart(registrationId, 12_34_57_00L);

        assertEquals(0, update.stageStartSetCount());
        assertEquals(1, update.stageStartDeleteCount());
        assertEquals(registrationId, update.stageStartDeleteRegistrationId(0));
    }

    @Test
    public void engineeringStageStartReplacesOneCompactUtcValue() {
        RaceDataState state = new RaceDataState(EventData.empty());
        RegistrationId registrationId = new RegistrationId("R0042");

        state.setStageStart(registrationId, 12_34_56_78L);
        assertEquals(Long.valueOf(12_34_56_78L), state.stageStartFor(registrationId));

        state.setStageStart(registrationId, 2026_10_23_12_34_57_12L);
        assertEquals(Long.valueOf(2026_10_23_12_34_57_12L), state.stageStartFor(registrationId));
    }
}
