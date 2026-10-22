package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.Collections;

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
