package io.github.brainboxemb.eventtiming.timingpoint.domain.node;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandlerTypes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
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
        activate(node.node());
        activate(node.upstreamHandler());
        try {
            stream.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "R0001",
                                            "0042")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));

            assertTrue(
                    node.upstreamHandler()
                            .inputAvailable());

            UpstreamData.Ack ack =
                    (UpstreamData.Ack) stream.takeSent();
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    ack.stream());
            assertEquals(1L, ack.sequence());
        } finally {
            deactivate(node.upstreamHandler());
            deactivate(node.node());
        }
    }

    @Test
    public void heartbeatUsesSameOutboundStreamAndTracksMissedIntervals() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node("A", stream);
        activate(node.node());
        activate(node.upstreamHandler());
        try {
            UpstreamHandler port =
                    node.upstreamHandler();

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
            deactivate(node.upstreamHandler());
            deactivate(node.node());
        }
    }

    /** Verifies that Task Lane work waits while LogBook I/O owns the shared worker. */
    @Test
    public void heartbeatIsQueuedToTaskLaneWhileDataLaneIsBusy()
            throws Exception {
        ScheduledThreadPoolExecutor nodeWorker =
                new ScheduledThreadPoolExecutor(1);
        nodeWorker.setRemoveOnCancelPolicy(true);
        ScheduledThreadPoolExecutor tagWorker =
                new ScheduledThreadPoolExecutor(1);
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
        TimeSource timeSource =
                () -> Instant.parse(
                        "2026-10-13T12:00:00Z");
        BlockingAppendPersistence persistence =
                new BlockingAppendPersistence();
        LogBook logBook =
                new LogBook(
                        new NodeId("A"),
                        persistence,
                        timeSource,
                        SystemMonotonicClock.INSTANCE,
                        new LogBookMetrics(),
                        nodeWorker,
                        4,
                        4,
                        LogBook.DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS,
                        LogBook.DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS);
        RaceDataState raceData = new RaceDataState(eventData);
        ActiveRegistrations activeRegistrations = new ActiveRegistrations();
        NodeService tasks =
                new NodeService(
                        new NodeId("A"),
                        nodeWorker,
                        logBook,
                        timeSource,
                        eventData,
                        raceData,
                        activeRegistrations);
        UpstreamHandler upstreamHandler =
                new UpstreamHandler(
                        new NodeId("A"),
                        nodeWorker,
                        stream,
                        logBook,
                        tasks::applyRaceData);
        TagProcessor tags =
                new TagProcessor(
                        eventData,
                        tagConfiguration,
                        SystemMonotonicClock.INSTANCE,
                        new TagProcessingMetrics(),
                        tagWorker);
        TimingNode timingNode =
                new TimingNode(
                        new AntennaId("1"),
                        timeSource,
                        logBook,
                        tasks,
                        tags,
                        nodeWorker);

        activate(timingNode);
        activate(upstreamHandler);
        try {
            awaitOperational(timingNode);
            // The real NODE_OPEN LogBook operation owns the Data Lane while append blocks.
            timingNode.open(new LocationId(24));
            assertTrue(
                    persistence.appendStarted.await(
                            1L,
                            TimeUnit.SECONDS));

            upstreamHandler.heartbeatTick();

            assertEquals(
                    1L,
                    heartbeatSent.getCount());

            persistence.releaseAppend.countDown();
            assertTrue(
                    heartbeatSent.await(
                            1,
                            TimeUnit.SECONDS));
        } finally {
            persistence.releaseAppend.countDown();
            deactivate(upstreamHandler);
            deactivate(timingNode);
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
        activate(node.node());
        activate(node.upstreamHandler());
        try {
            UpstreamHandler port =
                    node.upstreamHandler();
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

            long deadline =
                    System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(1L);
            while (port.heartbeatStatus().answersReceived() != 1L
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }

            UpstreamHandlerTypes.HeartbeatStatus status =
                    port.heartbeatStatus();
            assertEquals(1L, status.answersReceived());
            assertEquals(0L, status.missedIntervals());
            assertFalse(status.answerPending());
            assertEquals(0, stream.sentCount());
        } finally {
            deactivate(node.upstreamHandler());
            deactivate(node.node());
        }
    }

    @Test
    public void inboundPingRequestProducesAnswerOnSameStream() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node("A", stream);
        activate(node.node());
        activate(node.upstreamHandler());
        try {
            stream.receive(
                    DefaultUpstreamData.ping(
                            new NodeId("A"),
                            UpstreamData.PingOperation.REQ,
                            UpstreamData.PingMode.HB,
                            "peer-42"));

            assertTrue(
                    node.upstreamHandler()
                            .inputAvailable());

            UpstreamData.Ping answer =
                    (UpstreamData.Ping) stream.takeSent();
            assertSame(
                    UpstreamData.PingOperation.ANS,
                    answer.operation());
            assertEquals("peer-42", answer.pingId());
        } finally {
            deactivate(node.upstreamHandler());
            deactivate(node.node());
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
        activate(node.node());
        activate(node.upstreamHandler());
        try {
            assertFalse(
                    node.upstreamHandler()
                            .inputAvailable());
            assertEquals(0, stream.sentCount());
        } finally {
            deactivate(node.upstreamHandler());
            deactivate(node.node());
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
                        Collections.<TimingRecord>emptyList());
        TimingNodeFixture.Components populated =
                node(
                        "B",
                        populatedStream,
                        timingData("B", 3));
        activate(empty.node());
        activate(populated.node());
        activate(empty.upstreamHandler());
        activate(populated.upstreamHandler());
        try {
            // Ordered reads are the startup barrier for the published LogBook heads.
            assertEquals(
                    0,
                    empty.node().logBook().process(
                            LogBookQueries.timingDataCount()).intValue());
            assertEquals(
                    3,
                    populated.node().logBook().process(
                            LogBookQueries.timingDataCount()).intValue());

            empty.upstreamHandler()
                    .sendTimingDataSync();
            populated.upstreamHandler()
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
            deactivate(empty.upstreamHandler());
            deactivate(populated.upstreamHandler());
            deactivate(empty.node());
            deactivate(populated.node());
        }
    }

    @Test
    public void timingDataReqSendsOriginalRecordsFromInclusiveSequence() {
        List<TimingRecord> records =
                timingData("A", 5);
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node(
                        "A",
                        stream,
                        records);
        activate(node.node());
        activate(node.upstreamHandler());
        try {
            stream.receive(
                    DefaultUpstreamData.req(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            3L));

            assertTrue(
                    node.upstreamHandler()
                            .inputAvailable());

            UpstreamData.TimingDataTransfer transfer =
                    (UpstreamData.TimingDataTransfer)
                            stream.takeSent();
            assertEquals(3, transfer.records().size());
            assertEquals(
                    records.get(2),
                    transfer.records().get(0));
            assertEquals(
                    records.get(4),
                    transfer.records().get(2));
            assertEquals(
                    0,
                    stream.sentCount());
        } finally {
            deactivate(node.upstreamHandler());
            deactivate(node.node());
        }
    }

    @Test
    public void timingDataReqSendsAtMost256RecordsPerBatch() {
        List<TimingRecord> records =
                timingData("A", 257);
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node(
                        "A",
                        stream,
                        records);
        activate(node.node());
        activate(node.upstreamHandler());
        try {
            stream.receive(
                    DefaultUpstreamData.req(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            1L));

            assertTrue(
                    node.upstreamHandler()
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
            deactivate(node.upstreamHandler());
            deactivate(node.node());
        }
    }

    @Test
    public void timingDataSynchronizationStopsAtCapturedHeadWhenLogBookGrowsBetweenBatches() {
        List<TimingRecord> records =
                timingData("A", 257);
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node(
                        "A",
                        stream,
                        records);
        activate(node.node());
        activate(node.upstreamHandler());
        try {
            stream.onNextSend(
                    () -> node.node().logBook().execute(
                            LogBookCommands.commitNodeOpen(
                                    new LocationId(24))));

            stream.receive(
                    DefaultUpstreamData.req(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            1L));

            assertTrue(
                    node.upstreamHandler()
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
                    node.node().logBook().process(
                            LogBookQueries.timingDataHead(),
                            LogBookQuery.ReadConsistency.CURRENT)
                            .longValue());
            assertEquals(0, stream.sentCount());
        } finally {
            deactivate(node.upstreamHandler());
            deactivate(node.node());
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
        activate(node.node());
        activate(node.upstreamHandler());
        try {
            stream.receive(
                    DefaultUpstreamData.req(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            3L));

            assertTrue(
                    node.upstreamHandler()
                            .inputAvailable());
            assertEquals(0, stream.sentCount());
        } finally {
            deactivate(node.upstreamHandler());
            deactivate(node.node());
        }
    }

    @Test
    public void raceDataSyncAheadRequestsFirstMissingSequenceAndEqualIsQuiet() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node("A", stream);
        activate(node.node());
        activate(node.upstreamHandler());
        try {
            stream.receive(
                    DefaultUpstreamData.sync(
                            new NodeId("A"),
                            UpstreamData.Stream.RACE_DATA,
                            2L));
            assertTrue(
                    node.upstreamHandler()
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
                                            "R0001",
                                            "0042")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));
            assertTrue(
                    node.upstreamHandler()
                            .inputAvailable());
            stream.takeSent(); // cumulative RACE_DATA ACK

            stream.receive(
                    DefaultUpstreamData.sync(
                            new NodeId("A"),
                            UpstreamData.Stream.RACE_DATA,
                            1L));
            assertTrue(
                    node.upstreamHandler()
                            .inputAvailable());
            assertEquals(0, stream.sentCount());
        } finally {
            deactivate(node.upstreamHandler());
            deactivate(node.node());
        }
    }

    @Test
    public void timingDataSynchronizationRemainsNodeScoped() {
        InMemoryUpstreamStream streamA =
                new InMemoryUpstreamStream();
        InMemoryUpstreamStream streamB =
                new InMemoryUpstreamStream();
        List<TimingRecord> recordsA =
                timingData("A", 2);
        List<TimingRecord> recordsB =
                timingData("B", 3);
        TimingNodeFixture.Components nodeA =
                node("A", streamA, recordsA);
        TimingNodeFixture.Components nodeB =
                node("B", streamB, recordsB);
        activate(nodeA.node());
        activate(nodeB.node());
        activate(nodeA.upstreamHandler());
        activate(nodeB.upstreamHandler());
        try {
            streamA.receive(
                    DefaultUpstreamData.req(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            1L));
            assertTrue(
                    nodeA.upstreamHandler()
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
                    nodeB.upstreamHandler()
                            .inputAvailable());

            UpstreamData.TimingDataTransfer transferB =
                    (UpstreamData.TimingDataTransfer)
                            streamB.takeSent();
            assertEquals(2, transferB.records().size());
            assertEquals(
                    new NodeId("B"),
                    transferB.timingNodeId());
        } finally {
            deactivate(nodeA.upstreamHandler());
            deactivate(nodeB.upstreamHandler());
            deactivate(nodeA.node());
            deactivate(nodeB.node());
        }
    }

    private static void awaitOperational(TimingNode node)
            throws InterruptedException {
        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(1L);
        while (node.operationalState()
                        != TimingNodeTypes.OperationalState.OK
                && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertEquals(
                TimingNodeTypes.OperationalState.OK,
                node.operationalState());
    }

    private static TimingNodeFixture.Components node(
            String nodeId,
            UpstreamStream stream) {
        return node(
                nodeId,
                stream,
                Collections.<TimingRecord>emptyList());
    }

    private static TimingNodeFixture.Components node(
            String nodeId,
            UpstreamStream stream,
            List<TimingRecord> initialTimingData) {
        return TimingNodeFixture.createDirectComponents(
                new NodeId(nodeId),
                new MemoryPersistence(initialTimingData),
                () -> Instant.parse("2026-10-13T12:00:00Z"),
                EventData.empty(),
                stream);
    }

    private static List<TimingRecord> timingData(
            String nodeId,
            int count) {
        List<TimingRecord> records =
                new ArrayList<TimingRecord>(
                        count);
        Instant base =
                Instant.parse(
                        "2026-10-13T12:00:00Z");

        for (int index = 1;
                index <= count;
                index++) {
            long timestamp =
                    base.plusMillis(index)
                            .toEpochMilli();
            records.add(
                    TimingRecord.automatic(
                            nodeId.charAt(0),
                            index,
                            1,
                            timestamp,
                            timestamp,
                            new RegistrationId("A0001").packedValue(),
                            TimingRecord.Action.ADD,
                            null,
                            null));
        }
        return records;
    }

    private static final class BlockingAppendPersistence
            implements TimingDataPersistence {
        private final CountDownLatch appendStarted = new CountDownLatch(1);
        private final CountDownLatch releaseAppend = new CountDownLatch(1);

        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingRecord>emptyList(),
                    false);
        }

        @Override
        public void append(TimingRecord record) {
            appendStarted.countDown();
            try {
                releaseAppend.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while blocking test append",
                        ex);
            }
        }
    }

    private static final class MemoryPersistence
            implements TimingDataPersistence {
        private final List<TimingRecord> records =
                new ArrayList<TimingRecord>();

        private MemoryPersistence(
                List<TimingRecord> initialTimingData) {
            records.addAll(
                    initialTimingData);
        }

        @Override
        public synchronized LoadResult load() {
            return new LoadResult(
                    new ArrayList<TimingRecord>(
                            records),
                    false);
        }

        @Override
        public synchronized void append(
                TimingRecord record) {
            records.add(record);
        }
    }
}
