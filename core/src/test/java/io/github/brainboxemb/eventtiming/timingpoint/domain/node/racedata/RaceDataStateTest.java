package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RaceDataStateTest {

    @Test
    public void engineeringStageStartReplacesOneNormalizedValue() {
        RaceDataState state = new RaceDataState(EventData.empty());
        TeamId teamId = new TeamId("0042");

        state.setStageStart(teamId, 1798112096780L);
        assertEquals(
                Long.valueOf(1798112096780L),
                state.stageStartMillisFor(teamId));

        state.setStageStart(teamId, 1798112097120L);
        assertEquals(
                Long.valueOf(1798112097120L),
                state.stageStartMillisFor(teamId));
    }
}
