package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
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
        TimingNode node = node();
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            TimingNodeTypes.RaceDataApplyResult result =
                    port.applyRaceData(
                            raceDataSet(
                                    1L,
                                    "RT-R-0001",
                                    "0042"));

            assertSame(
                    TimingNodeTypes.RaceDataApplyResult.Outcome.APPLIED,
                    result.outcome());
            assertTrue(result.shouldAcknowledge());
            assertEquals(1L, result.acknowledgeSequence());
            assertEquals(1L, port.raceDataAppliedSequence());
            assertEquals(
                    new TeamId("0042"),
                    port.teamIdFor(
                            new RegistrationId("RT-R-0001")));
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void gapDoesNotApplyAndRequestsFirstMissingSequence() {
        TimingNode node = node();
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            TimingNodeTypes.RaceDataApplyResult result =
                    port.applyRaceData(
                            raceDataSet(
                                    2L,
                                    "RT-R-0001",
                                    "0042"));

            assertSame(
                    TimingNodeTypes.RaceDataApplyResult.Outcome.GAP,
                    result.outcome());
            assertTrue(result.requiresRaceDataRequest());
            assertEquals(1L, result.requestedFromSequence());
            assertEquals(0L, port.raceDataAppliedSequence());
            assertNull(
                    port.teamIdFor(
                            new RegistrationId("RT-R-0001")));
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void duplicateSequenceIsNotAppliedAgain() {
        TimingNode node = node();
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            port.applyRaceData(
                    raceDataSet(
                            1L,
                            "RT-R-0001",
                            "0042"));

            TimingNodeTypes.RaceDataApplyResult duplicate =
                    port.applyRaceData(
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
                    port.teamIdFor(
                            new RegistrationId("RT-R-0001")));
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void fixedEventDataTeamCannotBeOverridden() {
        TimingNode node = node();
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            TimingNodeTypes.RaceDataApplyResult result =
                    port.applyRaceData(
                            raceDataSet(
                                    1L,
                                    "RT-A-0001",
                                    "9999"));

            assertSame(
                    TimingNodeTypes.RaceDataApplyResult.Outcome.REJECTED,
                    result.outcome());
            assertEquals(0L, port.raceDataAppliedSequence());
            assertEquals(
                    new TeamId("0001"),
                    port.teamIdFor(
                            new RegistrationId("RT-A-0001")));
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void rejectedMessageIsAtomicAcrossRaceDataSections() {
        TimingNode node = node();
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            TimingNodeTypes.RaceDataApplyResult result =
                    port.applyRaceData(
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
            assertEquals(0L, port.raceDataAppliedSequence());
            assertNull(
                    port.stageStartTimeFor(
                            new TeamId("0042")));
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void stageStartTimesApplyIncrementallyWithRaceDataSequence() {
        TimingNode node = node();
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            TimingNodeTypes.RaceDataApplyResult set =
                    port.applyRaceData(
                            DefaultUpstreamData.raceData(
                                    new NodeId("A"),
                                    1L,
                                    Collections.<UpstreamData.ReserveTagMapChange>emptyList(),
                                    Collections.singletonList(
                                            DefaultUpstreamData.stageStartSet(
                                                    "0042",
                                                    LocalTime.of(12, 30)))));

            assertSame(
                    TimingNodeTypes.RaceDataApplyResult.Outcome.APPLIED,
                    set.outcome());
            assertEquals(
                    LocalTime.of(12, 30),
                    port.stageStartTimeFor(
                            new TeamId("0042"))
                            .startTime());

            TimingNodeTypes.RaceDataApplyResult delete =
                    port.applyRaceData(
                            DefaultUpstreamData.raceData(
                                    new NodeId("A"),
                                    2L,
                                    Collections.<UpstreamData.ReserveTagMapChange>emptyList(),
                                    Collections.singletonList(
                                            DefaultUpstreamData.stageStartDelete(
                                                    "0042"))));

            assertSame(
                    TimingNodeTypes.RaceDataApplyResult.Outcome.APPLIED,
                    delete.outcome());
            assertEquals(2L, port.raceDataAppliedSequence());
            assertNull(
                    port.stageStartTimeFor(
                            new TeamId("0042")));
        } finally {
            node.deactivate();
        }
    }

    private static UpstreamData.RaceData raceDataSet(
            long sequence,
            String registrationId,
            String teamId) {
        return DefaultUpstreamData.raceData(
                new NodeId("A"),
                sequence,
                Collections.singletonList(
                        DefaultUpstreamData.reserveTagSet(
                                registrationId,
                                teamId)),
                Collections.<UpstreamData.StageStartTimeChange>emptyList());
    }

    private static TimingNode node() {
        return new TimingNode(
                new NodeId("A"),
                new EmptyPersistence(),
                new DefaultTimingDataFactory(),
                () -> Instant.parse("2026-10-13T12:00:00Z"),
                new DefaultEventData());
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
            // RaceData-only tests do not commit TimingData.
        }
    }
}
