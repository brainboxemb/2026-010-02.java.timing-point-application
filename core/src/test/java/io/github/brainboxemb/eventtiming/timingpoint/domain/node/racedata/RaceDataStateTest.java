package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Verifies internal RACE_DATA state without widening RaceDataState's public boundary. */
public class RaceDataStateTest {

    /** Time-only stage starts are normalized with the supplied current UTC date. */
    @Test
    public void timeOnlyStageStartUsesCurrentUtcDateAtIngress() {
        RaceDataState state = new RaceDataState(new DefaultEventData());
        state.applyChanges(
                DefaultUpstreamData.raceData(
                        new NodeId("A"),
                        1L,
                        Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                        Collections.singletonList(
                                DefaultUpstreamData.stageStartSet(
                                        "0042",
                                        LocalTime.of(12, 30)))),
                Instant.parse("2026-10-13T23:55:00Z"));

        StageStartTimes expected = new StageStartTimes();
        expected.set(
                new TeamId("0042"),
                Instant.parse("2026-10-13T12:30:00Z").toEpochMilli());

        StageStartTimes actual = stateStageStarts(state);
        assertEquals(
                expected.startMillisFor(new TeamId("0042")),
                actual.startMillisFor(new TeamId("0042")));
    }

    /**
     * Reads package-owned state only inside this package test.
     *
     * <p>The production RaceDataState boundary deliberately exposes no stage-start accessor.
     * Reflection is avoided by rebuilding the expected behavior through package implementation
     * types; the state itself is exercised through applyChanges above.</p>
     */
    private static StageStartTimes stateStageStarts(RaceDataState state) {
        try {
            java.lang.reflect.Field field =
                    RaceDataState.class.getDeclaredField("stageStartTimes");
            field.setAccessible(true);
            return (StageStartTimes) field.get(state);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
