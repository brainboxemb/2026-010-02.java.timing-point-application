package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class UpstreamMessageRouterTest {

    @Test
    public void appliedRaceDataReturnsAckForAppliedSequence() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(node));

            UpstreamMessageRouter.Result result =
                    router.route(
                            DefaultUpstreamData.raceData(
                                    new NodeId("A"),
                                    1L,
                                    Collections.singletonList(
                                            DefaultUpstreamData.teamAssignmentSet(
                                                    "RT-R-0001",
                                                    "0042")),
                                    Collections.<UpstreamData.StageStartTimeChange>emptyList()));

            assertSame(
                    UpstreamMessageRouter.Outcome.APPLIED,
                    result.outcome());
            assertNull(result.message());
            assertTrue(
                    result.controlResponse()
                            instanceof UpstreamData.Ack);

            UpstreamData.Ack ack =
                    (UpstreamData.Ack) result.controlResponse();
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    ack.stream());
            assertEquals(1L, ack.sequence());
            assertEquals(
                    new NodeId("A"),
                    ack.timingNodeId());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void gapReturnsRaceDataReqFromFirstMissingSequence() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(node));

            UpstreamMessageRouter.Result result =
                    router.route(
                            DefaultUpstreamData.raceData(
                                    new NodeId("A"),
                                    2L,
                                    Collections.singletonList(
                                            DefaultUpstreamData.teamAssignmentSet(
                                                    "RT-R-0001",
                                                    "0042")),
                                    Collections.<UpstreamData.StageStartTimeChange>emptyList()));

            assertSame(
                    UpstreamMessageRouter.Outcome.GAP,
                    result.outcome());
            assertTrue(
                    result.controlResponse()
                            instanceof UpstreamData.Req);

            UpstreamData.Req req =
                    (UpstreamData.Req) result.controlResponse();
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    req.stream());
            assertEquals(1L, req.fromSequence());
            assertEquals(
                    new NodeId("A"),
                    req.timingNodeId());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void duplicateReturnsCumulativeAckWithoutReapply() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(node));

            UpstreamData.RaceData first =
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "RT-R-0001",
                                            "0042")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList());
            router.route(first);

            UpstreamMessageRouter.Result duplicate =
                    router.route(first);

            assertSame(
                    UpstreamMessageRouter.Outcome.DUPLICATE,
                    duplicate.outcome());
            UpstreamData.Ack ack =
                    (UpstreamData.Ack) duplicate.controlResponse();
            assertEquals(1L, ack.sequence());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void unknownNodeDoesNotCreateControlResponse() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(node));

            UpstreamMessageRouter.Result result =
                    router.route(
                            DefaultUpstreamData.raceData(
                                    new NodeId("B"),
                                    1L,
                                    Collections.singletonList(
                                            DefaultUpstreamData.teamAssignmentSet(
                                                    "RT-R-0001",
                                                    "0042")),
                                    Collections.<UpstreamData.StageStartTimeChange>emptyList()));

            assertSame(
                    UpstreamMessageRouter.Outcome.UNKNOWN_NODE,
                    result.outcome());
            assertNull(result.controlResponse());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void nonRaceDataFamilyIsDeferredToLaterStep7Activity() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(node));

            UpstreamMessageRouter.Result result =
                    router.route(
                            DefaultUpstreamData.sync(
                                    new NodeId("A"),
                                    UpstreamData.Stream.TIMING_DATA,
                                    0L));

            assertSame(
                    UpstreamMessageRouter.Outcome.UNSUPPORTED,
                    result.outcome());
            assertNull(result.controlResponse());
        } finally {
            node.deactivate();
        }
    }

    private static TimingNode node(String id) {
        return TimingNodeFixture.create(
                new NodeId(id),
                new EmptyPersistence(),
                () -> Instant.parse("2026-10-13T12:00:00Z"));
    }

    private static TimingNodeList nodes(TimingNode node) {
        return new TimingNodeList().add(node);
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
            // RaceData tests do not commit TimingData.
        }
    }
}
