package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TimingNodeRaceDataTest {

    @Test
    public void firstRaceDataSequenceAppliesAndBecomesAcknowledgeable() {
        TimingNodeLogic logic = logic();

        TimingNodeTypes.RaceDataApplyResult result =
                logic.applyRaceData(
                        DefaultUpstreamData.raceData(
                                new NodeId("A"),
                                1L,
                                Collections.singletonList(
                                        DefaultUpstreamData.reserveTagSet(
                                                "RT-R-0001",
                                                "0042")),
                                Collections.<UpstreamData.StageStartTimeChange>emptyList()));

        assertSame(
                TimingNodeTypes.RaceDataApplyResult.Outcome.APPLIED,
                result.outcome());
        assertTrue(result.shouldAcknowledge());
        assertEquals(1L, result.acknowledgeSequence());
        assertEquals(1L, logic.raceDataAppliedSequence());
        assertEquals(
                new TeamId("0042"),
                logic.teamIdFor(
                        new RegistrationId("RT-R-0001")));
    }

    @Test
    public void gapDoesNotApplyAndRequestsFirstMissingSequence() {
        TimingNodeLogic logic = logic();

        TimingNodeTypes.RaceDataApplyResult result =
                logic.applyRaceData(
                        DefaultUpstreamData.raceData(
                                new NodeId("A"),
                                2L,
                                Collections.singletonList(
                                        DefaultUpstreamData.reserveTagSet(
                                                "RT-R-0001",
                                                "0042")),
                                Collections.<UpstreamData.StageStartTimeChange>emptyList()));

        assertSame(
                TimingNodeTypes.RaceDataApplyResult.Outcome.GAP,
                result.outcome());
        assertTrue(result.requiresRaceDataRequest());
        assertEquals(1L, result.requestedFromSequence());
        assertEquals(0L, logic.raceDataAppliedSequence());
        assertNull(
                logic.teamIdFor(
                        new RegistrationId("RT-R-0001")));
    }

    @Test
    public void duplicateSequenceIsNotAppliedAgain() {
        TimingNodeLogic logic = logic();

        logic.applyRaceData(
                DefaultUpstreamData.raceData(
                        new NodeId("A"),
                        1L,
                        Collections.singletonList(
                                DefaultUpstreamData.reserveTagSet(
                                        "RT-R-0001",
                                        "0042")),
                        Collections.<UpstreamData.StageStartTimeChange>emptyList()));

        TimingNodeTypes.RaceDataApplyResult duplicate =
                logic.applyRaceData(
                        DefaultUpstreamData.raceData(
                                new NodeId("A"),
                                1L,
                                Collections.singletonList(
                                        DefaultUpstreamData.reserveTagDelete(
                                                "RT-R-0001")),
                                Collections.<UpstreamData.StageStartTimeChange>emptyList()));

        assertSame(
                TimingNodeTypes.RaceDataApplyResult.Outcome.DUPLICATE,
                duplicate.outcome());
        assertEquals(1L, duplicate.acknowledgeSequence());
        assertEquals(
                new TeamId("0042"),
                logic.teamIdFor(
                        new RegistrationId("RT-R-0001")));
    }

    @Test
    public void stableEventDataTeamCannotBeOverridden() {
        TimingNodeLogic logic = logic();

        TimingNodeTypes.RaceDataApplyResult result =
                logic.applyRaceData(
                        DefaultUpstreamData.raceData(
                                new NodeId("A"),
                                1L,
                                Collections.singletonList(
                                        DefaultUpstreamData.reserveTagSet(
                                                "RT-A-0001",
                                                "9999")),
                                Collections.<UpstreamData.StageStartTimeChange>emptyList()));

        assertSame(
                TimingNodeTypes.RaceDataApplyResult.Outcome.REJECTED,
                result.outcome());
        assertEquals(0L, logic.raceDataAppliedSequence());
        assertEquals(
                new TeamId("0001"),
                logic.teamIdFor(
                        new RegistrationId("RT-A-0001")));
    }

    @Test
    public void rejectedMessageIsAtomicAcrossRaceDataSections() {
        TimingNodeLogic logic = logic();

        TimingNodeTypes.RaceDataApplyResult result =
                logic.applyRaceData(
                        DefaultUpstreamData.raceData(
                                new NodeId("A"),
                                1L,
                                Collections.singletonList(
                                        DefaultUpstreamData.reserveTagSet(
                                                "RT-A-0001",
                                                "9999")),
                                Collections.singletonList(
                                        DefaultUpstreamData.stageStartSet(
                                                "0042",
                                                LocalTime.of(12, 0)))));

        assertSame(
                TimingNodeTypes.RaceDataApplyResult.Outcome.REJECTED,
                result.outcome());
        assertEquals(0L, logic.raceDataAppliedSequence());
        assertNull(logic.stageStartTimeFor(new TeamId("0042")));
    }

    @Test
    public void timingNodeCommandUsesOrderedNodeBoundary() {
        TimingNode node =
                new TimingNode(
                        new NodeId("A"),
                        new EmptyPersistence(),
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse("2026-10-13T12:00:00Z"));

        node.activate();
        try {
            TimingNodeTypes.RaceDataApplyResult result =
                    node.invoke(
                            TimingNodeCommands.applyRaceData(
                                    DefaultUpstreamData.raceData(
                                            new NodeId("A"),
                                            1L,
                                            Collections.singletonList(
                                                    DefaultUpstreamData.reserveTagSet(
                                                            "RT-R-0001",
                                                            "0042")),
                                            Collections.<UpstreamData.StageStartTimeChange>emptyList())));

            assertSame(
                    TimingNodeTypes.RaceDataApplyResult.Outcome.APPLIED,
                    result.outcome());
            assertEquals(
                    Long.valueOf(1L),
                    node.query(
                            TimingNodeQueries.raceDataAppliedSequence()));
            assertEquals(
                    new TeamId("0042"),
                    node.query(
                            TimingNodeQueries.teamIdFor(
                                    new RegistrationId("RT-R-0001"))));
        } finally {
            node.deactivate();
        }
    }

    private static TimingNodeLogic logic() {
        return new TimingNodeLogic(
                new NodeId("A"),
                new EmptyPersistence(),
                new DefaultTimingDataFactory(),
                () -> Instant.parse("2026-10-13T12:00:00Z"),
                new DefaultEventData(),
                SystemMonotonicClock.INSTANCE,
                new TimingNodeMetrics());
    }

    private static final class EmptyPersistence
            implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data) {
            // Not used by these RaceData-only tests.
        }
    }
}
