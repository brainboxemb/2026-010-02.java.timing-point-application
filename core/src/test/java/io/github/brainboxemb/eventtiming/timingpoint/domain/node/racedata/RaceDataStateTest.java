package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RaceDataStateTest {

    @Test
    public void raceDataStageStartSetAndDeleteUseRegistrationIdentity() {
        RaceDataState state = new RaceDataState(EventData.empty());
        RegistrationId registrationId = new RegistrationId("R0042");

        state.applyChanges(
                Collections.<RegistrationId, TeamId>emptyMap(),
                Collections.<RegistrationId>emptyList(),
                Collections.singletonMap(
                        registrationId,
                        Long.valueOf(1798112096780L)),
                Collections.<RegistrationId>emptyList());

        assertEquals(
                Long.valueOf(1798112096780L),
                state.stageStartMillisFor(registrationId));

        state.applyChanges(
                Collections.<RegistrationId, TeamId>emptyMap(),
                Collections.<RegistrationId>emptyList(),
                Collections.<RegistrationId, Long>emptyMap(),
                Collections.singletonList(registrationId));

        assertNull(state.stageStartMillisFor(registrationId));
    }

    @Test
    public void technicalVisitorsExposeOnlyLiveRaceDataState() {
        RegistrationId fixed = new RegistrationId("F0001");
        EventData eventData =
                new EventData(
                        Collections.emptyMap(),
                        Collections.singletonMap(fixed, new TeamId("0001")));
        RaceDataState state = new RaceDataState(eventData);
        RegistrationId first = new RegistrationId("R0042");
        RegistrationId second = new RegistrationId("R0043");

        state.applyChanges(
                Collections.singletonMap(first, new TeamId("0042")),
                Collections.<RegistrationId>emptyList(),
                Collections.singletonMap(second, Long.valueOf(1798112096780L)),
                Collections.<RegistrationId>emptyList());

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
                        (registrationId, startMillis) ->
                                stageStarts.add(
                                        registrationId.value()
                                                + "="
                                                + startMillis)));

        assertEquals(Collections.singletonList("R0042=0042"), mappings);
        assertEquals(new TeamId("0001"), state.teamIdFor(fixed));
        assertEquals(
                Collections.singletonList("R0043=1798112096780"),
                stageStarts);
    }

    @Test
    public void engineeringStageStartReplacesOneNormalizedValue() {
        RaceDataState state = new RaceDataState(EventData.empty());
        RegistrationId registrationId = new RegistrationId("R0042");

        state.setStageStart(registrationId, 1798112096780L);
        assertEquals(
                Long.valueOf(1798112096780L),
                state.stageStartMillisFor(registrationId));

        state.setStageStart(registrationId, 1798112097120L);
        assertEquals(
                Long.valueOf(1798112097120L),
                state.stageStartMillisFor(registrationId));
    }
}
