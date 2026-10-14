package io.github.brainboxemb.eventtiming.timingpoint.domain.node;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandlerTypes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class UpstreamHandlerTest {

    @Test
    public void raceDataIsReadFromStreamAndAckIsSentToStream() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node("A", stream);
        node.node().activate();
        try {
            stream.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "RT-R-0001",
                                            "0042")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));

            assertTrue(
                    node.nodeService().upstreamHandler()
                            .inputAvailable());

            UpstreamData.Ack ack =
                    (UpstreamData.Ack) stream.takeSent();
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    ack.stream());
            assertEquals(1L, ack.sequence());
        } finally {
            node.node().deactivate();
        }
    }

    @Test
    public void heartbeatUsesSameOutboundStreamAndTracksMissedIntervals() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node("A", stream);
        node.node().activate();
        try {
            UpstreamHandler port =
                    node.nodeService().upstreamHandler();

            port.heartbeatTick();
            UpstreamData.Ping first =
                    (UpstreamData.Ping) stream.takeSent();

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

            port.heartbeatTick();
            UpstreamData.Ping second =
                    (UpstreamData.Ping) stream.takeSent();
            assertEquals("hb-2", second.pingId());

            UpstreamHandlerTypes.HeartbeatStatus status =
                    port.heartbeatStatus();
            assertEquals(2L, status.requestsSent());
            assertEquals(0L, status.answersReceived());
            assertEquals(1L, status.missedIntervals());
            assertTrue(status.answerPending());
        } finally {
            node.node().deactivate();
        }
    }

    /** Verifies that a heartbeat is queued on the Task Lane while the Data Lane is occupied. */
    @Test
    public void heartbeatIsQueuedToTaskLaneWhileDataLaneIsBusy()
            throws Exception {
        ExecutorService nodeWorker =
                Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor tagWorker =
                new ScheduledThreadPoolExecutor(1);
        SerialLane dataLane =
                new SerialLane(
                        4,
                        "heartbeat-data-test",
                        nodeWorker);
        SerialLane taskLane =
                new SerialLane(
                        4,
                        "heartbeat-task-test",
                        nodeWorker);
        SerialLane tagLane =
                new SerialLane(
                        4,
                        "heartbeat-tag-test",
                        tagWorker);

        CountDownLatch heartbeatSent =
                new CountDownLatch(1);
        UpstreamStream stream =
                new UpstreamStream() {
                    @Override
                    public boolean isAvailable() {
                        return true;
                    }

                    @Override
                    public boolean hasMessage() {
                        return false;
                    }

                    @Override
                    public UpstreamData read() {
                        throw new IllegalStateException(
                                "no inbound data expected");
                    }

                    @Override
                    public void send(
                            UpstreamData message) {
                        heartbeatSent.countDown();
                    }
                };

        EventData eventData = EventData.empty();
        ReadOnlyConfiguration<TagProcessingPolicy> tagConfiguration =
                ReadOnlyConfiguration.fixed(
                        TagProcessingPolicy.defaults());
        LogBook logBook =
                new LogBook(
                        new NodeId("A"),
                        new MemoryPersistence(
                                Collections.<TimingData>emptyList()),
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse(
                                "2026-10-13T12:00:00Z"),
                        SystemMonotonicClock.INSTANCE,
                        new LogBookMetrics(),
                        dataLane,
                        LogBook.DEFAULT_OPERATION_TIMEOUT_MILLIS);
        NodeService tasks =
                new NodeService(
                        logBook,
                        taskLane,
                        eventData,
                        stream);
        TagProcessor tags =
                new TagProcessor(
                        eventData,
                        tagConfiguration,
                        SystemMonotonicClock.INSTANCE,
                        new TagProcessingMetrics(),
                        tagLane,
                        tagWorker);
        tagConfiguration.changes()
                .subscribe(
                        tags::onPolicyConfigurationChanged);
        TimingNode timingNode =
                new TimingNode(
                        logBook,
                        tasks,
                        tags);

        CountDownLatch blockerStarted =
                new CountDownLatch(1);
        CountDownLatch releaseBlocker =
                new CountDownLatch(1);

        timingNode.activate();
        try {
            // Barrier behind initial recovery before occupying the Data Lane.
            timingNode.logBook().query(LogBookQueries.status());

            Future<Void> blocker =
                    dataLane.submit(
                            () -> {
                                blockerStarted.countDown();
                                releaseBlocker.await();
                                return null;
                            });
            assertTrue(
                    blockerStarted.await(
                            1,
                            TimeUnit.SECONDS));

            long queuedBeforeHeartbeat =
                    taskLane.metrics()
                            .snapshot()
                            .queuedCount();

            tasks.upstreamHandler()
                    .heartbeatTick();

            assertEquals(
                    queuedBeforeHeartbeat + 1L,
                    taskLane.metrics()
                            .snapshot()
                            .queuedCount());
            assertEquals(
                    1L,
                    heartbeatSent.getCount());

            releaseBlocker.countDown();
            assertTrue(
                    heartbeatSent.await(
                            1,
                            TimeUnit.SECONDS));
        } finally {
            releaseBlocker.countDown();
            timingNode.deactivate();
            nodeWorker.shutdownNow();
            tagWorker.shutdownNow();
        }
    }

    @Test
    public void matchingHeartbeatAnswerIsReadFromStream() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node("A", stream);
        node.node().activate();
        try {
            UpstreamHandler port =
                    node.nodeService().upstreamHandler();
            port.heartbeatTick();
            UpstreamData.Ping request =
                    (UpstreamData.Ping) stream.takeSent();

            stream.receive(
                    DefaultUpstreamData.ping(
                            new NodeId("A"),
                            UpstreamData.PingOperation.ANS,
                            UpstreamData.PingMode.HB,
                            request.pingId()));
            assertTrue(port.inputAvailable());

            UpstreamHandlerTypes.HeartbeatStatus status =
                    port.heartbeatStatus();
            assertEquals(1L, status.answersReceived());
            assertEquals(0L, status.missedIntervals());
            assertFalse(status.answerPending());
            assertEquals(0, stream.sentCount());
        } finally {
            node.node().deactivate();
        }
    }

    @Test
    public void inboundPingRequestProducesAnswerOnSameStream() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node("A", stream);
        node.node().activate();
        try {
            stream.receive(
                    DefaultUpstreamData.ping(
                            new NodeId("A"),
                            UpstreamData.PingOperation.REQ,
                            UpstreamData.PingMode.HB,
                            "peer-42"));

            assertTrue(
                    node.nodeService().upstreamHandler()
                            .inputAvailable());

            UpstreamData.Ping answer =
                    (UpstreamData.Ping) stream.takeSent();
            assertSame(
                    UpstreamData.PingOperation.ANS,
                    answer.operation());
            assertEquals("peer-42", answer.pingId());
        } finally {
            node.node().deactivate();
        }
    }

    @Test
    public void unavailableStreamDoesNotScheduleNodeWork() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        stream.setAvailable(false);
        stream.receive(
                DefaultUpstreamData.ping(
                        new NodeId("A"),
                        UpstreamData.PingOperation.REQ,
                        null,
                        "manual-1"));

        TimingNodeFixture.Components node =
                node("A", stream);
        node.node().activate();
        try {
            assertFalse(
                    node.nodeService().upstreamHandler()
                            .inputAvailable());
            assertEquals(0, stream.sentCount());
        } finally {
            node.node().deactivate();
        }
    }

    @Test
    public void timingDataSyncAdvertisesEmptyAndCommittedSourceHead() {
        InMemoryUpstreamStream emptyStream =
                new InMemoryUpstreamStream();
        InMemoryUpstreamStream populatedStream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components empty =
                node(
                        "A",
                        emptyStream,
                        Collections.<TimingData>emptyList());
        TimingNodeFixture.Components populated =
                node(
                        "B",
                        populatedStream,
                        timingData("B", 3));
        empty.node().activate();
        populated.node().activate();
        try {
            empty.nodeService()
                    .upstreamHandler()
                    .sendTimingDataSync();
            populated.nodeService()
                    .upstreamHandler()
                    .sendTimingDataSync();

            UpstreamData.Sync emptySync =
                    (UpstreamData.Sync)
                            emptyStream.takeSent();
            UpstreamData.Sync populatedSync =
                    (UpstreamData.Sync)
                            populatedStream.takeSent();

            assertSame(
                    UpstreamData.Stream.TIMING_DATA,
                    emptySync.stream());
            assertEquals(0L, emptySync.sequence());
            assertEquals(
                    new NodeId("B"),
                    populatedSync.timingNodeId());
            assertEquals(3L, populatedSync.sequence());
        } finally {
            empty.node().deactivate();
            populated.node().deactivate();
        }
    }

    @Test
    public void timingDataReqSendsOriginalRecordsFromInclusiveSequence() {
        List<TimingData> records =
                timingData("A", 5);
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node(
                        "A",
                        stream,
                        records);
        node.node().activate();
        try {
            stream.receive(
                    DefaultUpstreamData.req(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            3L));

            assertTrue(
                    node.nodeService().upstreamHandler()
                            .inputAvailable());

            UpstreamData.TimingDataTransfer transfer =
                    (UpstreamData.TimingDataTransfer)
                            stream.takeSent();
            assertEquals(3, transfer.records().size());
            assertSame(
                    records.get(2),
                    transfer.records().get(0));
            assertSame(
                    records.get(4),
                    transfer.records().get(2));
            assertEquals(
                    0,
                    stream.sentCount());
        } finally {
            node.node().deactivate();
        }
    }

    @Test
    public void timingDataReqSendsAtMost256RecordsPerBatch() {
        List<TimingData> records =
                timingData("A", 257);
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node(
                        "A",
                        stream,
                        records);
        node.node().activate();
        try {
            stream.receive(
                    DefaultUpstreamData.req(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            1L));

            assertTrue(
                    node.nodeService().upstreamHandler()
                            .inputAvailable());

            UpstreamData.TimingDataTransfer first =
                    (UpstreamData.TimingDataTransfer)
                            stream.takeSent();
            UpstreamData.TimingDataTransfer second =
                    (UpstreamData.TimingDataTransfer)
                            stream.takeSent();

            assertEquals(256, first.records().size());
            assertEquals(
                    1L,
                    first.records().get(0).sequenceNumber());
            assertEquals(
                    256L,
                    first.records().get(255).sequenceNumber());
            assertEquals(1, second.records().size());
            assertEquals(
                    257L,
                    second.records().get(0).sequenceNumber());
            assertEquals(0, stream.sentCount());
        } finally {
            node.node().deactivate();
        }
    }

    @Test
    public void timingDataSynchronizationStopsAtCapturedHeadWhenLogBookGrowsBetweenBatches() {
        List<TimingData> records =
                timingData("A", 257);
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node(
                        "A",
                        stream,
                        records);
        node.node().activate();
        try {
            stream.onNextSend(
                    () -> node.node().logBook().queue(
                            LogBookCommands.open(
                                    new LocationId(24))));

            stream.receive(
                    DefaultUpstreamData.req(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            1L));

            assertTrue(
                    node.nodeService().upstreamHandler()
                            .inputAvailable());

            UpstreamData.TimingDataTransfer first =
                    (UpstreamData.TimingDataTransfer)
                            stream.takeSent();
            UpstreamData.TimingDataTransfer second =
                    (UpstreamData.TimingDataTransfer)
                            stream.takeSent();

            assertEquals(256, first.records().size());
            assertEquals(1, second.records().size());
            assertEquals(
                    257L,
                    second.records().get(0).sequenceNumber());
            assertEquals(
                    258L,
                    node.node().logBook().query(
                            LogBookQueries.timingDataHead(),
                            LogBookQuery.ReadConsistency.CURRENT)
                            .longValue());
            assertEquals(0, stream.sentCount());
        } finally {
            node.node().deactivate();
        }
    }

    @Test
    public void requestPastTimingDataHeadSendsNothing() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node(
                        "A",
                        stream,
                        timingData("A", 2));
        node.node().activate();
        try {
            stream.receive(
                    DefaultUpstreamData.req(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            3L));

            assertTrue(
                    node.nodeService().upstreamHandler()
                            .inputAvailable());
            assertEquals(0, stream.sentCount());
        } finally {
            node.node().deactivate();
        }
    }

    @Test
    public void raceDataSyncAheadRequestsFirstMissingSequenceAndEqualIsQuiet() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node("A", stream);
        node.node().activate();
        try {
            stream.receive(
                    DefaultUpstreamData.sync(
                            new NodeId("A"),
                            UpstreamData.Stream.RACE_DATA,
                            2L));
            assertTrue(
                    node.nodeService().upstreamHandler()
                            .inputAvailable());

            UpstreamData.Req req =
                    (UpstreamData.Req)
                            stream.takeSent();
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    req.stream());
            assertEquals(1L, req.fromSequence());

            stream.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "RT-R-0001",
                                            "0042")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));
            assertTrue(
                    node.nodeService().upstreamHandler()
                            .inputAvailable());
            stream.takeSent(); // cumulative RACE_DATA ACK

            stream.receive(
                    DefaultUpstreamData.sync(
                            new NodeId("A"),
                            UpstreamData.Stream.RACE_DATA,
                            1L));
            assertTrue(
                    node.nodeService().upstreamHandler()
                            .inputAvailable());
            assertEquals(0, stream.sentCount());
        } finally {
            node.node().deactivate();
        }
    }

    @Test
    public void timingDataSynchronizationRemainsNodeScoped() {
        InMemoryUpstreamStream streamA =
                new InMemoryUpstreamStream();
        InMemoryUpstreamStream streamB =
                new InMemoryUpstreamStream();
        List<TimingData> recordsA =
                timingData("A", 2);
        List<TimingData> recordsB =
                timingData("B", 3);
        TimingNodeFixture.Components nodeA =
                node("A", streamA, recordsA);
        TimingNodeFixture.Components nodeB =
                node("B", streamB, recordsB);
        nodeA.node().activate();
        nodeB.node().activate();
        try {
            streamA.receive(
                    DefaultUpstreamData.req(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            1L));
            assertTrue(
                    nodeA.nodeService().upstreamHandler()
                            .inputAvailable());

            UpstreamData.TimingDataTransfer transferA =
                    (UpstreamData.TimingDataTransfer)
                            streamA.takeSent();
            assertEquals(2, transferA.records().size());
            assertEquals(
                    new NodeId("A"),
                    transferA.timingNodeId());
            assertEquals(0, streamB.sentCount());

            streamB.receive(
                    DefaultUpstreamData.req(
                            new NodeId("B"),
                            UpstreamData.Stream.TIMING_DATA,
                            2L));
            assertTrue(
                    nodeB.nodeService().upstreamHandler()
                            .inputAvailable());

            UpstreamData.TimingDataTransfer transferB =
                    (UpstreamData.TimingDataTransfer)
                            streamB.takeSent();
            assertEquals(2, transferB.records().size());
            assertEquals(
                    new NodeId("B"),
                    transferB.timingNodeId());
        } finally {
            nodeA.node().deactivate();
            nodeB.node().deactivate();
        }
    }

    private static TimingNodeFixture.Components node(
            String nodeId,
            UpstreamStream stream) {
        return node(
                nodeId,
                stream,
                Collections.<TimingData>emptyList());
    }

    private static TimingNodeFixture.Components node(
            String nodeId,
            UpstreamStream stream,
            List<TimingData> initialTimingData) {
        return TimingNodeFixture.createDirectComponents(
                new NodeId(nodeId),
                new MemoryPersistence(initialTimingData),
                new DefaultTimingDataFactory(),
                () -> Instant.parse("2026-10-13T12:00:00Z"),
                EventData.empty(),
                stream);
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
                    new ArrayList<TimingData>(
                            records),
                    false);
        }

        @Override
        public synchronized void append(
                TimingData data) {
            records.add(data);
        }
    }
}
