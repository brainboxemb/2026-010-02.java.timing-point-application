package oss.relay.timing.timingpoint.domain.node;

import oss.relay.timing.eventdata.EventData;
import oss.relay.timing.timingdata.TimingDataTypes.LocationId;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingTimestamp;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookCommands;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookQueries;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookQuery;
import oss.relay.timing.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import oss.relay.timing.timingpoint.domain.system.upstream.node.NodeUpstreamTypes;
import oss.relay.timing.timingpoint.testsupport.TimingNodeFixture;
import oss.relay.timing.upstreamdata.UpstreamData;
import oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static oss.relay.timing.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static oss.relay.timing.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class NodeUpstreamTest {

    @Test
    public void raceDataIsAppliedBeforeContainingPacketIsConfirmed() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components node = node("A", stream);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.receive(racePacket(1L, "R0001", "0042"));

            UpstreamData confirmation = stream.takeSent();
            assertSame(UpstreamData.Family.RECV, confirmation.family());
            assertEquals(
                    1L,
                    ((UpstreamData.Receive) confirmation.message())
                            .receivedPacketNr());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void queuedDownstreamPacketsAreProcessedInPacketOrder() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components node = node("A", stream);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.receive(racePacket(1L, "R0001", "0042"));
            stream.receive(racePacket(2L, "R0002", "0043"));

            assertReceive(stream.takeSent(), 1L);
            assertReceive(stream.takeSent(), 2L);
            assertEquals(0, stream.sentCount());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void gapReportsLastValidPacketAndResendCanThenAdvance() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components node = node("A", stream);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.receive(racePacket(2L, "R0002", "0043"));
            assertReceive(stream.takeSent(), 0L);

            stream.receive(racePacket(1L, "R0001", "0042"));
            assertReceive(stream.takeSent(), 1L);

            stream.receive(racePacket(2L, "R0002", "0043"));
            assertReceive(stream.takeSent(), 2L);
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void failedPacketDoesNotBlockFollowingQueuedPacket() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components node = node("A", stream);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.receive(
                    DefaultUpstreamData.createUpPacket(
                            new NodeId("A"),
                            1L,
                            DefaultUpstreamData.createSequenceNotification(0L)));
            stream.receive(racePacket(1L, "R0001", "0042"));

            assertReceive(stream.takeSent(), 1L);
            assertEquals(0, stream.sentCount());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void freshConnectionSendsRecvZeroSequenceNotificationAndPingZero() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        stream.setAvailable(false);
        TimingNodeFixture.Components node = node("A", stream);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.connect();

            UpstreamData receive = stream.takeSent();
            UpstreamData sequence = stream.takeSent();
            UpstreamData ping = stream.takeSent();

            assertSame(UpstreamData.Direction.UP_PACKET, receive.direction());
            assertReceive(receive, 0L);

            UpstreamData.Sequence notification =
                    (UpstreamData.Sequence) sequence.message();
            assertSame(
                    UpstreamData.SequenceOperation.NTF,
                    notification.operation());
            assertEquals(Long.valueOf(0L), notification.sequence());

            assertEquals(0L, ping.packetNr());
            assertSame(UpstreamData.Family.PING, ping.family());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void heartbeatUsesPacketZeroAndMatchingAnswerUpdatesStatus() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        stream.setAvailable(false);
        TimingNodeFixture.Components node = node("A", stream);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.connect();
            stream.takeSent(); // RECV 0
            stream.takeSent(); // SEQ NTF
            UpstreamData requestPacket = stream.takeSent();

            assertEquals(0L, requestPacket.packetNr());
            UpstreamData.Ping request =
                    (UpstreamData.Ping) requestPacket.message();

            stream.receive(
                    DefaultUpstreamData.createDnPacket(
                            new NodeId("A"),
                            0L,
                            DefaultUpstreamData.createPing(
                                    UpstreamData.PingOperation.ANS,
                                    UpstreamData.PingMode.HB,
                                    request.pingId())));

            long deadline =
                    System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(1L);
            while (node.nodeUpstream()
                            .heartbeatStatus()
                            .answersReceived() != 1L
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }

            NodeUpstreamTypes.HeartbeatStatus status =
                    node.nodeUpstream().heartbeatStatus();
            assertEquals(1L, status.answersReceived());
            assertEquals(0L, status.missedIntervals());
            assertFalse(status.answerPending());
            assertEquals(0, stream.sentCount());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void inboundPingRequestProducesImmediatePacketZeroAnswerWithoutRecv() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components node = node("A", stream);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.receive(
                    DefaultUpstreamData.createDnPacket(
                            new NodeId("A"),
                            0L,
                            DefaultUpstreamData.createPing(
                                    UpstreamData.PingOperation.REQ,
                                    UpstreamData.PingMode.HB,
                                    "peer-42",
                                    TimingTimestamp.parseAbsolute(
                                            "2026-10-13T11:59:00Z"))));

            UpstreamData answerPacket = stream.takeSent();
            assertEquals(0L, answerPacket.packetNr());
            UpstreamData.Ping answer =
                    (UpstreamData.Ping) answerPacket.message();
            assertSame(
                    UpstreamData.PingOperation.ANS,
                    answer.operation());
            assertEquals("peer-42", answer.pingId());
            assertEquals(
                    Instant.parse("2026-10-13T12:00:00Z"),
                    answer.time().instant());
            assertEquals(0, stream.sentCount());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void connectionSequenceNotificationUsesCurrentLogBookHead() {
        InMemoryUpstreamStream emptyStream = new InMemoryUpstreamStream();
        InMemoryUpstreamStream populatedStream = new InMemoryUpstreamStream();
        emptyStream.setAvailable(false);
        populatedStream.setAvailable(false);

        TimingNodeFixture.Components empty = node("A", emptyStream);
        TimingNodeFixture.Components populated =
                node("B", populatedStream, timingData("B", 3));
        activate(empty.node());
        activate(populated.node());
        activate(empty.nodeUpstream());
        activate(populated.nodeUpstream());
        try {
            assertEquals(
                    0,
                    empty.node().logBook()
                            .process(LogBookQueries.timingDataCount())
                            .intValue());
            assertEquals(
                    3,
                    populated.node().logBook()
                            .process(LogBookQueries.timingDataCount())
                            .intValue());

            emptyStream.connect();
            populatedStream.connect();

            emptyStream.takeSent(); // RECV 0
            populatedStream.takeSent(); // RECV 0
            UpstreamData emptySequence = emptyStream.takeSent();
            UpstreamData populatedSequence = populatedStream.takeSent();
            emptyStream.takeSent(); // PING
            populatedStream.takeSent(); // PING

            assertEquals(
                    Long.valueOf(0L),
                    ((UpstreamData.Sequence) emptySequence.message())
                            .sequence());
            assertEquals(
                    Long.valueOf(3L),
                    ((UpstreamData.Sequence) populatedSequence.message())
                            .sequence());
        } finally {
            deactivate(empty.nodeUpstream());
            deactivate(populated.nodeUpstream());
            deactivate(empty.node());
            deactivate(populated.node());
        }
    }

    @Test
    public void sequenceRequestReturnsHeadThenRecvConfirmsRequestPacket() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node("A", stream, timingData("A", 2));
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.receive(
                    DefaultUpstreamData.createDnPacket(
                            new NodeId("A"),
                            1L,
                            DefaultUpstreamData.createSequenceRequest()));

            UpstreamData answerPacket = stream.takeSent();
            UpstreamData.Sequence answer =
                    (UpstreamData.Sequence) answerPacket.message();
            assertSame(
                    UpstreamData.SequenceOperation.ANS,
                    answer.operation());
            assertEquals(Long.valueOf(2L), answer.sequence());

            assertReceive(stream.takeSent(), 1L);
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void timingDataRequestConfirmsPacketThenReplaysOriginalRecords() {
        List<TimingRecord> records = timingData("A", 5);
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components node = node("A", stream, records);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.receive(timingDataRequestPacket("A", 1L, 3L));

            assertReceive(stream.takeSent(), 1L);
            UpstreamData.TimingDataTransfer transfer =
                    transfer(stream.takeSent());
            assertEquals(3, transfer.records().size());
            assertEquals(records.get(2), transfer.records().get(0));
            assertEquals(records.get(4), transfer.records().get(2));
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void timingDataRequestUsesAtMostSixteenRecordsPerMessage() {
        List<TimingRecord> records = timingData("A", 17);
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components node = node("A", stream, records);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.receive(timingDataRequestPacket("A", 1L, 1L));

            assertReceive(stream.takeSent(), 1L);
            UpstreamData.TimingDataTransfer first =
                    transfer(stream.takeSent());
            UpstreamData.TimingDataTransfer second =
                    transfer(stream.takeSent());

            assertEquals(
                    UpstreamData.MAX_TIMING_DATA_RECORDS,
                    first.records().size());
            assertEquals(1L, first.records().get(0).sequenceNumber());
            assertEquals(16L, first.records().get(15).sequenceNumber());
            assertEquals(1, second.records().size());
            assertEquals(17L, second.records().get(0).sequenceNumber());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void replayStopsAtCapturedHeadThenLiveCatchUpSendsLaterCommit() {
        List<TimingRecord> records = timingData("A", 17);
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components node = node("A", stream, records);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.onNextSend(
                    () -> node.node().logBook().execute(
                            LogBookCommands.commitNodeOpen(
                                    new LocationId(24))));

            stream.receive(timingDataRequestPacket("A", 1L, 1L));

            assertReceive(stream.takeSent(), 1L);
            UpstreamData.TimingDataTransfer first =
                    transfer(stream.takeSent());
            UpstreamData.TimingDataTransfer second =
                    transfer(stream.takeSent());
            UpstreamData.TimingDataTransfer live =
                    transfer(stream.takeSent());

            assertEquals(
                    UpstreamData.MAX_TIMING_DATA_RECORDS,
                    first.records().size());
            assertEquals(1, second.records().size());
            assertEquals(17L, second.records().get(0).sequenceNumber());
            assertEquals(1, live.records().size());
            assertEquals(18L, live.records().get(0).sequenceNumber());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void emptyReplayRequestCompletesHandoffWithoutTimingDataPayload() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components node =
                node("A", stream, timingData("A", 2));
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.receive(timingDataRequestPacket("A", 1L, 3L));
            assertReceive(stream.takeSent(), 1L);
            assertEquals(0, stream.sentCount());

            node.logBook().process(
                    LogBookCommands.commitNodeOpen(
                            new LocationId(24)));
            UpstreamData.TimingDataTransfer live =
                    transfer(stream.takeSent());
            assertEquals(3L, live.records().get(0).sequenceNumber());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void commitBeforeReplayRequestUsesLiveForwarding() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        stream.setAvailable(false);
        TimingNodeFixture.Components node = node("A", stream);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.connect();
            stream.takeSent(); // RECV 0
            UpstreamData sequencePacket = stream.takeSent();
            stream.takeSent(); // PING
            assertEquals(
                    Long.valueOf(0L),
                    ((UpstreamData.Sequence) sequencePacket.message())
                            .sequence());

            node.logBook().process(
                    LogBookCommands.commitNodeOpen(
                            new LocationId(24)));
            UpstreamData.TimingDataTransfer liveBeforeReplay =
                    transfer(stream.takeSent());
            assertEquals(
                    1L,
                    liveBeforeReplay.records().get(0).sequenceNumber());

            stream.receive(timingDataRequestPacket("A", 1L, 1L));
            assertReceive(stream.takeSent(), 1L);
            UpstreamData.TimingDataTransfer replay =
                    transfer(stream.takeSent());
            assertEquals(1L, replay.records().get(0).sequenceNumber());

            node.logBook().process(
                    LogBookCommands.commitNodeClose(
                            new LocationId(24)));
            UpstreamData.TimingDataTransfer live =
                    transfer(stream.takeSent());
            assertEquals(2L, live.records().get(0).sequenceNumber());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void reconnectKeepsDownstreamPositionAndLiveForwardingUntilReplay() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        stream.setAvailable(false);
        TimingNodeFixture.Components node = node("A", stream);
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.connect();
            stream.takeSent(); // RECV 0
            stream.takeSent(); // SEQ NTF
            stream.takeSent(); // PING

            stream.receive(timingDataRequestPacket("A", 1L, 1L));
            assertReceive(stream.takeSent(), 1L);

            node.logBook().process(
                    LogBookCommands.commitNodeOpen(
                            new LocationId(24)));
            assertEquals(
                    1L,
                    transfer(stream.takeSent())
                            .records().get(0).sequenceNumber());

            stream.disconnect();
            stream.connect();
            UpstreamData reconnectPosition = stream.takeSent();
            UpstreamData reconnectSequence = stream.takeSent();
            UpstreamData reconnectPing = stream.takeSent();
            assertReceive(reconnectPosition, 1L);
            assertSame(UpstreamData.Family.SEQ, reconnectSequence.family());
            assertSame(UpstreamData.Family.PING, reconnectPing.family());
            assertEquals(0, stream.sentCount());

            node.logBook().process(
                    LogBookCommands.commitNodeClose(
                            new LocationId(24)));
            UpstreamData.TimingDataTransfer liveBeforeReplay =
                    transfer(stream.takeSent());
            assertEquals(
                    2L,
                    liveBeforeReplay.records().get(0).sequenceNumber());

            stream.receive(timingDataRequestPacket("A", 2L, 2L));
            assertReceive(stream.takeSent(), 2L);
            assertEquals(
                    2L,
                    transfer(stream.takeSent())
                            .records().get(0).sequenceNumber());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void caughtUpReconnectNeedsNoPeerSequenceToContinueLiveForwarding() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        stream.setAvailable(false);
        TimingNodeFixture.Components node =
                node("A", stream, timingData("A", 1));
        activate(node.node());
        activate(node.nodeUpstream());
        try {
            stream.connect();
            stream.takeSent(); // RECV 0
            UpstreamData sourceHead = stream.takeSent();
            stream.takeSent(); // PING
            assertSame(
                    UpstreamData.Family.SEQ,
                    sourceHead.family());
            assertEquals(
                    Long.valueOf(1L),
                    ((UpstreamData.Sequence) sourceHead.message())
                            .sequence());
            assertEquals(0, stream.sentCount());

            node.logBook().process(
                    LogBookCommands.commitNodeClose(
                            new LocationId(24)));
            UpstreamData.TimingDataTransfer live =
                    transfer(stream.takeSent());
            assertEquals(
                    2L,
                    live.records().get(0).sequenceNumber());
        } finally {
            deactivate(node.nodeUpstream());
            deactivate(node.node());
        }
    }

    @Test
    public void timingDataSynchronizationRemainsNodeScoped() {
        InMemoryUpstreamStream streamA = new InMemoryUpstreamStream();
        InMemoryUpstreamStream streamB = new InMemoryUpstreamStream();
        TimingNodeFixture.Components nodeA =
                node("A", streamA, timingData("A", 2));
        TimingNodeFixture.Components nodeB =
                node("B", streamB, timingData("B", 3));
        activate(nodeA.node());
        activate(nodeB.node());
        activate(nodeA.nodeUpstream());
        activate(nodeB.nodeUpstream());
        try {
            streamA.receive(timingDataRequestPacket("A", 1L, 1L));
            assertReceive(streamA.takeSent(), 1L);
            UpstreamData transferA = streamA.takeSent();
            assertEquals(
                    new NodeId("A"),
                    transferA.timingNodeId());
            assertEquals(0, streamB.sentCount());

            streamB.receive(timingDataRequestPacket("B", 1L, 2L));
            assertReceive(streamB.takeSent(), 1L);
            UpstreamData transferB = streamB.takeSent();
            assertEquals(
                    new NodeId("B"),
                    transferB.timingNodeId());
        } finally {
            deactivate(nodeA.nodeUpstream());
            deactivate(nodeB.nodeUpstream());
            deactivate(nodeA.node());
            deactivate(nodeB.node());
        }
    }

    private static UpstreamData racePacket(
            long packetNr,
            String registrationId,
            String teamId) {
        return DefaultUpstreamData.createDnPacket(
                new NodeId("A"),
                packetNr,
                DefaultUpstreamData.createRaceData(
                        new LocationId(1),
                        Collections.singletonList(
                                DefaultUpstreamData.createTeamAssignmentSet(
                                        registrationId,
                                        teamId)),
                        Collections.<UpstreamData.StageStartTimeChange>emptyList()));
    }

    private static UpstreamData timingDataRequestPacket(
            String nodeId,
            long packetNr,
            long fromSequence) {
        return DefaultUpstreamData.createDnPacket(
                new NodeId(nodeId),
                packetNr,
                DefaultUpstreamData.createTimingDataRequest(fromSequence));
    }

    private static UpstreamData.TimingDataTransfer transfer(
            UpstreamData packet) {
        assertSame(UpstreamData.Family.TIMING_DATA, packet.family());
        return (UpstreamData.TimingDataTransfer) packet.message();
    }

    private static void assertReceive(
            UpstreamData packet,
            long receivedPacketNr) {
        assertSame(UpstreamData.Family.RECV, packet.family());
        assertEquals(
                receivedPacketNr,
                ((UpstreamData.Receive) packet.message())
                        .receivedPacketNr());
    }

    private static TimingNodeFixture.Components node(
            String nodeId,
            InMemoryUpstreamStream stream) {
        return node(
                nodeId,
                stream,
                Collections.<TimingRecord>emptyList());
    }

    private static TimingNodeFixture.Components node(
            String nodeId,
            InMemoryUpstreamStream stream,
            List<TimingRecord> initialTimingData) {
        return TimingNodeFixture.createDirectComponents(
                new NodeId(nodeId),
                new MemoryPersistence(initialTimingData),
                () -> Instant.parse("2026-10-13T12:00:00Z"),
                EventData.empty(),
                stream.bind(new NodeId(nodeId)));
    }

    private static List<TimingRecord> timingData(
            String nodeId,
            int count) {
        List<TimingRecord> records =
                new ArrayList<TimingRecord>(count);
        Instant base =
                Instant.parse("2026-10-13T12:00:00Z");

        for (int index = 1; index <= count; index++) {
            long timestamp =
                    base.plusMillis(index * 10L)
                            .toEpochMilli();
            records.add(
                    TimingRecord.createAutomaticRegistration(
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

    private static final class MemoryPersistence
            implements TimingDataPersistence {
        private final List<TimingRecord> records =
                new ArrayList<TimingRecord>();

        private MemoryPersistence(
                List<TimingRecord> initialTimingData) {
            records.addAll(initialTimingData);
        }

        @Override
        public synchronized LoadResult load() {
            return new LoadResult(
                    new ArrayList<TimingRecord>(records),
                    false);
        }

        @Override
        public synchronized void append(
                TimingRecord record) {
            records.add(record);
        }
    }
}
