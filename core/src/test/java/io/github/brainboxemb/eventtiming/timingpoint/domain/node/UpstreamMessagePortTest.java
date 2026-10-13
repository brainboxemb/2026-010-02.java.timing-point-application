package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UpstreamMessagePortTest {

    @Test
    public void genericHandleOwnsRaceDataAckSemantics() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            List<UpstreamData> responses =
                    port.handle(
                            DefaultUpstreamData.raceData(
                                    new NodeId("A"),
                                    1L,
                                    Collections.singletonList(
                                            DefaultUpstreamData.teamAssignmentSet(
                                                    "RT-R-0001",
                                                    "0042")),
                                    Collections.<UpstreamData.StageStartTimeChange>emptyList()));

            assertEquals(
                    1,
                    responses.size());
            assertTrue(
                    responses.get(0)
                            instanceof UpstreamData.Ack);
            assertEquals(
                    1L,
                    ((UpstreamData.Ack) responses.get(0))
                            .sequence());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void timingDataSyncExposesCommittedSourceHead() {
        TimingNode empty = node("A");
        TimingNode populated =
                node(
                        "B",
                        timingData("B", 3));
        empty.activate();
        populated.activate();
        try {
            UpstreamData.Sync emptySync =
                    empty.upstreamMessagePort()
                            .timingDataSync();
            UpstreamData.Sync populatedSync =
                    populated.upstreamMessagePort()
                            .timingDataSync();

            assertSame(
                    UpstreamData.Stream.TIMING_DATA,
                    emptySync.stream());
            assertEquals(
                    0L,
                    emptySync.sequence());
            assertEquals(
                    new NodeId("B"),
                    populatedSync.timingNodeId());
            assertEquals(
                    3L,
                    populatedSync.sequence());
        } finally {
            empty.deactivate();
            populated.deactivate();
        }
    }

    @Test
    public void timingDataReqReplaysOriginalRecordsFromInclusiveSequence() {
        List<TimingData> records =
                timingData(
                        "A",
                        5);
        TimingNode node =
                node(
                        "A",
                        records);
        node.activate();
        try {
            List<UpstreamData> responses =
                    node.upstreamMessagePort()
                            .handle(
                                    DefaultUpstreamData.req(
                                            new NodeId("A"),
                                            UpstreamData.Stream.TIMING_DATA,
                                            3L));

            assertEquals(
                    1,
                    responses.size());
            UpstreamData.TimingDataTransfer transfer =
                    (UpstreamData.TimingDataTransfer) responses.get(0);
            assertEquals(
                    3,
                    transfer.records().size());
            assertSame(
                    records.get(2),
                    transfer.records().get(0));
            assertSame(
                    records.get(4),
                    transfer.records().get(2));
            assertEquals(
                    3L,
                    transfer.records().get(0).sequenceNumber());
            assertEquals(
                    5L,
                    transfer.records().get(2).sequenceNumber());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void timingDataReqUsesBoundedReplayBatches() {
        TimingNode node =
                node(
                        "A",
                        timingData(
                                "A",
                                257));
        node.activate();
        try {
            List<UpstreamData> responses =
                    node.upstreamMessagePort()
                            .handle(
                                    DefaultUpstreamData.req(
                                            new NodeId("A"),
                                            UpstreamData.Stream.TIMING_DATA,
                                            1L));

            assertEquals(
                    2,
                    responses.size());

            UpstreamData.TimingDataTransfer first =
                    (UpstreamData.TimingDataTransfer) responses.get(0);
            UpstreamData.TimingDataTransfer second =
                    (UpstreamData.TimingDataTransfer) responses.get(1);

            assertEquals(
                    256,
                    first.records().size());
            assertEquals(
                    1L,
                    first.records().get(0).sequenceNumber());
            assertEquals(
                    256L,
                    first.records().get(255).sequenceNumber());
            assertEquals(
                    1,
                    second.records().size());
            assertEquals(
                    257L,
                    second.records().get(0).sequenceNumber());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void requestPastTimingDataHeadReturnsNoMessages() {
        TimingNode node =
                node(
                        "A",
                        timingData(
                                "A",
                                2));
        node.activate();
        try {
            List<UpstreamData> responses =
                    node.upstreamMessagePort()
                            .handle(
                                    DefaultUpstreamData.req(
                                            new NodeId("A"),
                                            UpstreamData.Stream.TIMING_DATA,
                                            3L));

            assertTrue(
                    responses.isEmpty());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void raceDataSyncEqualAheadAndBehindHaveExplicitSemantics() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            assertTrue(
                    port.handle(
                            DefaultUpstreamData.sync(
                                    new NodeId("A"),
                                    UpstreamData.Stream.RACE_DATA,
                                    0L))
                            .isEmpty());

            List<UpstreamData> ahead =
                    port.handle(
                            DefaultUpstreamData.sync(
                                    new NodeId("A"),
                                    UpstreamData.Stream.RACE_DATA,
                                    2L));
            assertEquals(
                    1,
                    ahead.size());
            UpstreamData.Req req =
                    (UpstreamData.Req) ahead.get(0);
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    req.stream());
            assertEquals(
                    1L,
                    req.fromSequence());

            port.handle(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "RT-R-0001",
                                            "0042")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));

            assertTrue(
                    port.handle(
                            DefaultUpstreamData.sync(
                                    new NodeId("A"),
                                    UpstreamData.Stream.RACE_DATA,
                                    1L))
                            .isEmpty());

            try {
                port.handle(
                        DefaultUpstreamData.sync(
                                new NodeId("A"),
                                UpstreamData.Stream.RACE_DATA,
                                0L));
                fail("expected source-head conflict");
            } catch (IllegalStateException expected) {
                assertTrue(
                        expected.getMessage()
                                .contains("behind"));
            }
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void wrongDirectionReqAndSyncAreUnsupported() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            try {
                port.handle(
                        DefaultUpstreamData.req(
                                new NodeId("A"),
                                UpstreamData.Stream.RACE_DATA,
                                1L));
                fail("expected unsupported REQ direction");
            } catch (UnsupportedOperationException expected) {
                assertTrue(
                        expected.getMessage()
                                .contains("TIMING_DATA"));
            }

            try {
                port.handle(
                        DefaultUpstreamData.sync(
                                new NodeId("A"),
                                UpstreamData.Stream.TIMING_DATA,
                                0L));
                fail("expected unsupported SYNC direction");
            } catch (UnsupportedOperationException expected) {
                assertTrue(
                        expected.getMessage()
                                .contains("RACE_DATA"));
            }
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void heartbeatTickIsNodeScopedAndTracksMissedIntervals() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            UpstreamData.Ping first =
                    port.heartbeatTick();

            assertEquals(
                    new NodeId("A"),
                    first.timingNodeId());
            assertSame(
                    UpstreamData.PingOperation.REQ,
                    first.operation());
            assertSame(
                    UpstreamData.PingMode.HB,
                    first.mode());
            assertEquals("hb-1", first.pingId());

            UpstreamMessagePort.HeartbeatStatus firstStatus =
                    port.heartbeatStatus();
            assertEquals(1L, firstStatus.requestsSent());
            assertEquals(0L, firstStatus.answersReceived());
            assertEquals(0L, firstStatus.missedIntervals());
            assertTrue(firstStatus.answerPending());

            UpstreamData.Ping second =
                    port.heartbeatTick();
            assertEquals("hb-2", second.pingId());

            UpstreamMessagePort.HeartbeatStatus secondStatus =
                    port.heartbeatStatus();
            assertEquals(2L, secondStatus.requestsSent());
            assertEquals(1L, secondStatus.missedIntervals());
            assertTrue(secondStatus.answerPending());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void matchingHeartbeatAnswerClearsPendingState() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();
            UpstreamData.Ping request =
                    port.heartbeatTick();

            UpstreamData.Ping response =
                    port.handlePing(
                            DefaultUpstreamData.ping(
                                    new NodeId("A"),
                                    UpstreamData.PingOperation.ANS,
                                    UpstreamData.PingMode.HB,
                                    request.pingId()));

            assertNull(response);

            UpstreamMessagePort.HeartbeatStatus status =
                    port.heartbeatStatus();
            assertEquals(1L, status.answersReceived());
            assertEquals(0L, status.missedIntervals());
            assertFalse(status.answerPending());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void staleHeartbeatAnswerDoesNotAcknowledgeCurrentRequest() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();
            port.heartbeatTick();
            port.heartbeatTick();

            port.handlePing(
                    DefaultUpstreamData.ping(
                            new NodeId("A"),
                            UpstreamData.PingOperation.ANS,
                            UpstreamData.PingMode.HB,
                            "hb-1"));

            UpstreamMessagePort.HeartbeatStatus status =
                    port.heartbeatStatus();
            assertEquals(0L, status.answersReceived());
            assertEquals(1L, status.missedIntervals());
            assertTrue(status.answerPending());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void inboundPingRequestProducesAnswerWithoutAnotherRequest() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            UpstreamData.Ping answer =
                    port.handlePing(
                            DefaultUpstreamData.ping(
                                    new NodeId("A"),
                                    UpstreamData.PingOperation.REQ,
                                    UpstreamData.PingMode.HB,
                                    "peer-42"));

            assertEquals(
                    new NodeId("A"),
                    answer.timingNodeId());
            assertSame(
                    UpstreamData.PingOperation.ANS,
                    answer.operation());
            assertSame(
                    UpstreamData.PingMode.HB,
                    answer.mode());
            assertEquals("peer-42", answer.pingId());

            UpstreamMessagePort.HeartbeatStatus status =
                    port.heartbeatStatus();
            assertEquals(0L, status.requestsSent());
            assertEquals(0L, status.answersReceived());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void pingForDifferentNodeIsRejected() {
        TimingNode node = node("A");
        node.activate();
        try {
            try {
                node.upstreamMessagePort()
                        .handlePing(
                                DefaultUpstreamData.ping(
                                        new NodeId("B"),
                                        UpstreamData.PingOperation.REQ,
                                        null,
                                        "manual-1"));
                fail("expected node mismatch");
            } catch (IllegalArgumentException expected) {
                assertTrue(
                        expected.getMessage()
                                .contains("NodeId"));
            }
        } finally {
            node.deactivate();
        }
    }

    private static TimingNode node(String nodeId) {
        return node(
                nodeId,
                Collections.<TimingData>emptyList());
    }

    private static TimingNode node(
            String nodeId,
            List<TimingData> initialTimingData) {
        return new TimingNode(
                new NodeId(nodeId),
                new MemoryPersistence(initialTimingData),
                new DefaultTimingDataFactory(),
                () -> Instant.parse("2026-10-13T12:00:00Z"),
                EventData.empty());
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
