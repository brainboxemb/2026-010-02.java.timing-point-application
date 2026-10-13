package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UpstreamMessageRouterTest {

    @Test
    public void raceDataIsDispatchedToOwningNode() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(node));

            List<UpstreamData> responses =
                    router.route(
                            raceData(
                                    "A",
                                    1L));

            assertEquals(
                    1,
                    responses.size());
            assertTrue(
                    responses.get(0)
                            instanceof UpstreamData.Ack);

            UpstreamData.Ack ack =
                    (UpstreamData.Ack) responses.get(0);
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    ack.stream());
            assertEquals(
                    1L,
                    ack.sequence());
            assertEquals(
                    new NodeId("A"),
                    ack.timingNodeId());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void raceDataGapResponseComesFromNodePort() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(node));

            List<UpstreamData> responses =
                    router.route(
                            raceData(
                                    "A",
                                    2L));

            assertEquals(
                    1,
                    responses.size());
            assertTrue(
                    responses.get(0)
                            instanceof UpstreamData.Req);

            UpstreamData.Req req =
                    (UpstreamData.Req) responses.get(0);
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    req.stream());
            assertEquals(
                    1L,
                    req.fromSequence());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void duplicateRaceDataStillReturnsCumulativeAck() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(node));
            UpstreamData.RaceData first =
                    raceData(
                            "A",
                            1L);

            router.route(first);
            List<UpstreamData> responses =
                    router.route(first);

            assertEquals(
                    1,
                    responses.size());
            UpstreamData.Ack ack =
                    (UpstreamData.Ack) responses.get(0);
            assertEquals(
                    1L,
                    ack.sequence());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void pingIsDispatchedWithoutRouterProtocolLogic() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(node));

            List<UpstreamData> responses =
                    router.route(
                            DefaultUpstreamData.ping(
                                    new NodeId("A"),
                                    UpstreamData.PingOperation.REQ,
                                    UpstreamData.PingMode.HB,
                                    "peer-42"));

            assertEquals(
                    1,
                    responses.size());
            UpstreamData.Ping answer =
                    (UpstreamData.Ping) responses.get(0);
            assertSame(
                    UpstreamData.PingOperation.ANS,
                    answer.operation());
            assertEquals(
                    "peer-42",
                    answer.pingId());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void unknownNodeFailsBeforeSemanticHandling() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(node));

            try {
                router.route(
                        raceData(
                                "B",
                                1L));
                fail("expected unknown node");
            } catch (IllegalArgumentException expected) {
                assertTrue(
                        expected.getMessage()
                                .contains("Unknown TimingNode"));
            }
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void timingDataReplayIsRoutedToSelectedNodeOnly() {
        TimingNode nodeA =
                node(
                        "A",
                        timingData(
                                "A",
                                3));
        TimingNode nodeB =
                node(
                        "B",
                        timingData(
                                "B",
                                2));
        nodeA.activate();
        nodeB.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(
                                    nodeA,
                                    nodeB));

            List<UpstreamData> responses =
                    router.route(
                            DefaultUpstreamData.req(
                                    new NodeId("B"),
                                    UpstreamData.Stream.TIMING_DATA,
                                    1L));

            assertEquals(
                    1,
                    responses.size());
            UpstreamData.TimingDataTransfer transfer =
                    (UpstreamData.TimingDataTransfer) responses.get(0);
            assertEquals(
                    2,
                    transfer.records().size());
            for (TimingData record : transfer.records()) {
                assertEquals(
                        new NodeId("B"),
                        record.timingNodeId());
            }
        } finally {
            nodeA.deactivate();
            nodeB.deactivate();
        }
    }

    @Test
    public void unsupportedFamilyIsA_nodePortConcern() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessageRouter router =
                    new UpstreamMessageRouter(
                            nodes(node));

            try {
                router.route(
                        DefaultUpstreamData.sync(
                                new NodeId("A"),
                                UpstreamData.Stream.TIMING_DATA,
                                0L));
                fail("expected unsupported family");
            } catch (UnsupportedOperationException expected) {
                assertTrue(
                        expected.getMessage()
                                .contains("SYNC"));
            }
        } finally {
            node.deactivate();
        }
    }

    private static UpstreamData.RaceData raceData(
            String nodeId,
            long sequence) {
        return DefaultUpstreamData.raceData(
                new NodeId(nodeId),
                sequence,
                Collections.singletonList(
                        DefaultUpstreamData.teamAssignmentSet(
                                "RT-R-0001",
                                "0042")),
                Collections.<UpstreamData.StageStartTimeChange>emptyList());
    }

    private static TimingNode node(String id) {
        return node(
                id,
                Collections.<TimingData>emptyList());
    }

    private static TimingNode node(
            String id,
            List<TimingData> initialTimingData) {
        return TimingNodeFixture.create(
                new NodeId(id),
                new MemoryPersistence(initialTimingData),
                () -> Instant.parse("2026-10-13T12:00:00Z"));
    }

    private static TimingNodeList nodes(
            TimingNode... timingNodes) {
        TimingNodeList result =
                new TimingNodeList();
        for (TimingNode timingNode : timingNodes) {
            result.add(timingNode);
        }
        return result;
    }

    private static List<TimingData> timingData(
            String nodeId,
            int count) {
        DefaultTimingDataFactory factory =
                new DefaultTimingDataFactory();
        List<TimingData> records =
                new ArrayList<TimingData>(
                        count);
        Instant base =
                Instant.parse(
                        "2026-10-13T12:00:00Z");

        for (int index = 1;
                index <= count;
                index++) {
            TimingTimestamp timestamp =
                    new TimingTimestamp(
                            base.plusMillis(index));
            records.add(
                    factory.createAutomaticRegistration(
                            new TimingDataFactory.Context(
                                    new NodeId(nodeId),
                                    index,
                                    new LocationId(1),
                                    timestamp,
                                    timestamp),
                            new RegistrationId(
                                    "RT-A-0001")));
        }
        return records;
    }

    private static final class MemoryPersistence
            implements TimingDataPersistence {
        private final List<TimingData> records =
                new ArrayList<TimingData>();

        private MemoryPersistence(
                List<TimingData> initialTimingData) {
            records.addAll(
                    initialTimingData);
        }

        @Override
        public synchronized LoadResult load() {
            return new LoadResult(
                    new ArrayList<TimingData>(records),
                    false);
        }

        @Override
        public synchronized void append(
                TimingData data) {
            records.add(data);
        }
    }
}
