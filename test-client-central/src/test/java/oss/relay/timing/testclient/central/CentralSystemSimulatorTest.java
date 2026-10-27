package oss.relay.timing.testclient.central;

import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingTimestamp;
import oss.relay.timing.upstreamdata.UpstreamData;
import oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CentralSystemSimulatorTest {
    private static final Instant RECEIVED =
            Instant.parse("2026-10-24T08:00:00Z");
    private static final NodeId NODE_A = new NodeId("A");

    @Test
    void sequenceNotificationRequestsMissingTimingDataWithNormalDnPacket() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(7));
        simulator.connected();

        List<UpstreamData> replies =
                simulator.accept(
                        upPacket(
                                1L,
                                DefaultUpstreamData.createSequenceNotification(4L)),
                        RECEIVED);

        assertEquals(1, replies.size());
        UpstreamData requestPacket = replies.get(0);
        assertSame(
                UpstreamData.Direction.DN_PACKET,
                requestPacket.direction());
        assertEquals(1L, requestPacket.packetNr());
        assertEquals(
                1L,
                ((UpstreamData.TimingDataRequest) requestPacket.message())
                        .fromSequence());
    }

    @Test
    void freshCentralAdoptsRunningNodeDownstreamPositionBeforeReplayRequest() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(7));
        simulator.connected();

        assertTrue(
                simulator.accept(
                                receivePacket(183L, 23L),
                                RECEIVED)
                        .isEmpty());

        List<UpstreamData> replies =
                simulator.accept(
                        upPacket(
                                184L,
                                DefaultUpstreamData.createSequenceNotification(1184L)),
                        RECEIVED.plusMillis(1L));

        assertEquals(1, replies.size());
        UpstreamData request = replies.get(0);
        assertEquals(24L, request.packetNr());
        assertEquals(
                UpstreamData.Family.TIMING_DATA_REQ,
                request.family());
        assertEquals(
                1L,
                ((UpstreamData.TimingDataRequest) request.message())
                        .fromSequence());

        CentralSystemSimulator.NodeView view =
                simulator.nodes().get(0);
        assertEquals(23L, view.lastDnPacketAcknowledged());
        assertEquals(Long.valueOf(24L), view.outstandingDnPacketNr());
    }

    @Test
    void lateRunningNodePositionRenumbersFreshCentralOutstandingPacket() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(7));
        simulator.connected();

        UpstreamData firstRequest =
                simulator.accept(
                                upPacket(
                                        183L,
                                        DefaultUpstreamData.createSequenceNotification(1184L)),
                                RECEIVED)
                        .get(0);
        assertEquals(1L, firstRequest.packetNr());

        List<UpstreamData> renumbered =
                simulator.accept(
                        receivePacket(184L, 23L),
                        RECEIVED.plusMillis(1L));

        assertEquals(1, renumbered.size());
        UpstreamData request = renumbered.get(0);
        assertEquals(24L, request.packetNr());
        assertEquals(
                UpstreamData.Family.TIMING_DATA_REQ,
                request.family());
        assertEquals(
                1L,
                ((UpstreamData.TimingDataRequest) request.message())
                        .fromSequence());
    }

    @Test
    void sequenceRequestAnswersCentralProcessedPosition() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(7));
        simulator.connected();
        simulator.accept(
                timingDataPacket(1L, opened(1L, 7)),
                RECEIVED);

        List<UpstreamData> replies =
                simulator.accept(
                        upPacket(
                                2L,
                                DefaultUpstreamData.createSequenceRequest()),
                        RECEIVED.plusSeconds(1));

        assertEquals(1, replies.size());
        UpstreamData.Sequence answer =
                (UpstreamData.Sequence) replies.get(0).message();
        assertSame(
                UpstreamData.SequenceOperation.ANS,
                answer.operation());
        assertEquals(Long.valueOf(1L), answer.sequence());
    }

    @Test
    void manualSourceHeadQueryUsesSeqRequestWithoutStartingTimingDataSync() {
        CentralSystemSimulator simulator =
                openSimulatorAtLocation(7);

        List<UpstreamData> packets =
                simulator.requestSourceHead(NODE_A);

        assertEquals(1, packets.size());
        UpstreamData requestPacket = packets.get(0);
        assertEquals(1L, requestPacket.packetNr());
        assertEquals(UpstreamData.Family.SEQ, requestPacket.family());
        assertSame(
                UpstreamData.SequenceOperation.REQ,
                ((UpstreamData.Sequence) requestPacket.message())
                        .operation());
        assertTrue(
                simulator.nodes().get(0).sourceHeadQueryPending());

        List<UpstreamData> onAnswer =
                simulator.accept(
                        upPacket(
                                2L,
                                DefaultUpstreamData.createSequenceAnswer(5L)),
                        RECEIVED.plusSeconds(1));

        assertTrue(onAnswer.isEmpty());
        CentralSystemSimulator.NodeView answered =
                simulator.nodes().get(0);
        assertEquals(Long.valueOf(5L), answered.sourceHead());
        assertFalse(answered.sourceHeadQueryPending());
        assertSame(
                requestPacket,
                simulator.outstandingPacket(NODE_A));

        List<UpstreamData> afterAck =
                simulator.accept(
                        receivePacket(
                                3L,
                                requestPacket.packetNr()),
                        RECEIVED.plusSeconds(2));
        assertTrue(afterAck.isEmpty());
    }

    @Test
    void duplicateManualSourceHeadQueryIsRejectedWhileAnswerIsPending() {
        CentralSystemSimulator simulator =
                openSimulatorAtLocation(7);

        simulator.requestSourceHead(NODE_A);

        assertThrows(
                IllegalStateException.class,
                () -> simulator.requestSourceHead(NODE_A));
    }

    @Test
    void caughtUpSequenceNeedsNoAutomaticCentralResponse() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(7));
        simulator.connected();
        simulator.accept(
                timingDataPacket(1L, opened(1L, 7)),
                RECEIVED);

        List<UpstreamData> replies =
                simulator.accept(
                        upPacket(
                                2L,
                                DefaultUpstreamData.createSequenceNotification(1L)),
                        RECEIVED.plusSeconds(1));

        assertTrue(replies.isEmpty());
    }

    @Test
    void missingTimingDataStillRequestsCentralNextExpectedSequence() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(7));
        simulator.connected();
        simulator.accept(
                timingDataPacket(1L, opened(1L, 7)),
                RECEIVED);

        List<UpstreamData> replies =
                simulator.accept(
                        upPacket(
                                2L,
                                DefaultUpstreamData.createSequenceNotification(4L)),
                        RECEIVED.plusSeconds(1));

        assertEquals(1, replies.size());
        UpstreamData requestPacket = replies.get(0);
        assertEquals(
                UpstreamData.Family.TIMING_DATA_REQ,
                requestPacket.family());
        assertEquals(
                2L,
                ((UpstreamData.TimingDataRequest) requestPacket.message())
                        .fromSequence());
    }

    @Test
    void newerSourceHeadDoesNotQueueDuplicateAutomaticReplayRequest() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(7));
        simulator.connected();

        UpstreamData request =
                simulator.accept(
                        upPacket(
                                1L,
                                DefaultUpstreamData.createSequenceNotification(4L)),
                        RECEIVED)
                        .get(0);
        assertEquals(
                1L,
                ((UpstreamData.TimingDataRequest) request.message())
                        .fromSequence());

        List<UpstreamData> retry =
                simulator.accept(
                        upPacket(
                                2L,
                                DefaultUpstreamData.createSequenceNotification(5L)),
                        RECEIVED.plusSeconds(1));
        assertEquals(1, retry.size());
        assertSame(request, retry.get(0));

        List<UpstreamData> afterAck =
                simulator.accept(
                        receivePacket(3L, request.packetNr()),
                        RECEIVED.plusSeconds(2));
        assertTrue(afterAck.isEmpty());
    }

    @Test
    void stagesExposeConfiguredAndAuthoredCentralState() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(2, 1));

        assertEquals(
                List.of(1, 2),
                simulator.stages().stream()
                        .map(CentralSystemSimulator.StageView::stageId)
                        .toList());
        assertTrue(
                simulator.stages().get(0)
                        .startTimes().isEmpty());

        simulator.updateStageStarts(
                4,
                List.of(
                        DefaultUpstreamData.createStageStartSet(
                                "A0002",
                                TimingTimestamp.parse("10:30:00")),
                        DefaultUpstreamData.createStageStartSet(
                                "A0003",
                                TimingTimestamp.parse("10:30:15"))));

        assertEquals(
                List.of(1, 2, 4),
                simulator.stages().stream()
                        .map(CentralSystemSimulator.StageView::stageId)
                        .toList());
        CentralSystemSimulator.StageView stage4 =
                simulator.stages().get(2);
        assertEquals(2, stage4.startTimes().size());
        assertEquals(
                "A0002",
                stage4.startTimes().get(0).registrationId());
        assertEquals(
                "10:30:15",
                stage4.startTimes().get(1).start());
    }

    @Test
    void historicalOpenDoesNotOverrideCurrentRuntimeClosedState() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(2));
        simulator.connected();
        assertTrue(
                simulator.applyRuntimeStatus(
                                "A",
                                "CLOSED",
                                null,
                                RECEIVED)
                        .isEmpty());

        assertTrue(
                simulator.updateStageStarts(
                                2,
                                List.of(
                                        DefaultUpstreamData.createStageStartSet(
                                                "A0001",
                                                TimingTimestamp.parse("19:00:00"))))
                        .isEmpty());

        UpstreamData request =
                simulator.accept(
                                upPacket(
                                        1L,
                                        DefaultUpstreamData.createSequenceNotification(2L)),
                                RECEIVED)
                        .get(0);
        assertEquals(
                UpstreamData.Family.TIMING_DATA_REQ,
                request.family());

        assertTrue(
                simulator.accept(
                                receivePacket(
                                        2L,
                                        request.packetNr()),
                                RECEIVED.plusMillis(1L))
                        .isEmpty());

        assertTrue(
                simulator.accept(
                                timingDataPacket(
                                        3L,
                                        opened(1L, 2)),
                                RECEIVED.plusMillis(2L))
                        .isEmpty());

        TimingRecord close =
                TimingRecord.createNodeClosed(
                        'A',
                        2L,
                        2,
                        RECEIVED.plusMillis(3L).toEpochMilli(),
                        RECEIVED.plusMillis(3L).toEpochMilli());
        assertTrue(
                simulator.accept(
                                timingDataPacket(
                                        4L,
                                        close),
                                RECEIVED.plusMillis(3L))
                        .isEmpty());

        assertEquals(
                "CLOSED",
                simulator.nodes().get(0).lifecycleState());
        assertNull(simulator.nodes().get(0).locationId());
        assertFalse(
                "OPEN".equals(location(simulator, 2).state()),
                "historical LogBook OPEN must not mark LocationId 2 current");
    }

    @Test
    void currentRuntimeOpenReleasesStoredStageStarts() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(2));
        simulator.connected();

        simulator.updateStageStarts(
                2,
                List.of(
                        DefaultUpstreamData.createStageStartSet(
                                "A0001",
                                TimingTimestamp.parse("19:00:00"))));

        List<UpstreamData> replies =
                simulator.applyRuntimeStatus(
                        "A",
                        "OPEN",
                        Integer.valueOf(2),
                        RECEIVED);

        assertEquals(1, replies.size());
        assertEquals(
                UpstreamData.Family.RACE_DATA,
                replies.get(0).family());
        UpstreamData.RaceData raceData =
                (UpstreamData.RaceData) replies.get(0).message();
        assertEquals(2, raceData.locationId().value());
        assertEquals(1, raceData.stageStartTimeChanges().size());
        assertEquals(
                "A0001",
                raceData.stageStartTimeChanges()
                        .get(0)
                        .registrationId()
                        .value());
    }

    @Test
    void centralStageStartStateRoutesStageNOnlyToLocationN() {
        CentralSystemSimulator simulator =
                openSimulatorAtLocation(2);

        List<UpstreamData> wrongStage =
                simulator.updateStageStarts(
                        1,
                        List.of(
                                DefaultUpstreamData.createStageStartSet(
                                        "A0001",
                                        TimingTimestamp.parse("10:00:00"))));

        assertTrue(wrongStage.isEmpty());

        List<UpstreamData> matchingStage =
                simulator.updateStageStarts(
                        2,
                        List.of(
                                DefaultUpstreamData.createStageStartSet(
                                        "A0001",
                                        TimingTimestamp.parse("10:05:00"))));

        assertEquals(1, matchingStage.size());
        UpstreamData.RaceData raceData =
                (UpstreamData.RaceData) matchingStage.get(0).message();
        assertEquals(2, raceData.locationId().value());
        assertEquals(1, raceData.stageStartTimeChanges().size());
        assertEquals(
                "A0001",
                raceData.stageStartTimeChanges()
                        .get(0)
                        .registrationId()
                        .value());
        assertEquals(
                "10:05:00",
                raceData.stageStartTimeChanges()
                        .get(0)
                        .start()
                        .toString());
    }

    @Test
    void centralStageStartCanBeAuthoredBeforeNodeOpens() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(2));
        simulator.connected();

        assertTrue(
                simulator.updateStageStarts(
                                2,
                                List.of(
                                        DefaultUpstreamData.createStageStartSet(
                                                "A0001",
                                                TimingTimestamp.parse("10:05:00"))))
                        .isEmpty());

        List<UpstreamData> afterHistoricalOpen =
                simulator.accept(
                        timingDataPacket(
                                1L,
                                opened(1L, 2)),
                        RECEIVED);

        assertTrue(afterHistoricalOpen.isEmpty());

        List<UpstreamData> afterRuntimeOpen =
                simulator.applyRuntimeStatus(
                        "A",
                        "OPEN",
                        Integer.valueOf(2),
                        RECEIVED.plusMillis(1L));

        assertEquals(1, afterRuntimeOpen.size());
        UpstreamData.RaceData raceData =
                (UpstreamData.RaceData) afterRuntimeOpen.get(0).message();
        assertEquals(2, raceData.locationId().value());
        assertEquals(1, raceData.stageStartTimeChanges().size());
    }

    @Test
    void globalTeamMappingIsDeliveredToAnOpenNode() {
        CentralSystemSimulator simulator =
                openSimulatorAtLocation(7);

        List<UpstreamData> packets =
                simulator.updateTeamAssignments(
                        List.of(
                                DefaultUpstreamData.createTeamAssignmentSet(
                                        "A0001",
                                        "T001")));

        assertEquals(1, packets.size());
        UpstreamData.RaceData raceData =
                (UpstreamData.RaceData) packets.get(0).message();
        assertEquals(7, raceData.locationId().value());
        assertEquals(1, raceData.teamAssignmentChanges().size());
    }

    @Test
    void raceDataUsesOneImmutableOutstandingPacketAndBuildsNextDeltaAfterRecv() {
        CentralSystemSimulator simulator =
                openSimulatorAtLocation(7);

        List<UpstreamData> first =
                simulator.updateRaceData(
                        NODE_A,
                        List.of(
                                DefaultUpstreamData.createTeamAssignmentSet(
                                        "A0001",
                                        "T001")),
                        List.of());

        assertEquals(1, first.size());
        UpstreamData packet1 = first.get(0);
        assertEquals(1L, packet1.packetNr());
        assertSame(
                UpstreamData.Family.RACE_DATA,
                packet1.family());

        List<UpstreamData> whileOutstanding =
                simulator.updateRaceData(
                        NODE_A,
                        List.of(
                                DefaultUpstreamData.createTeamAssignmentSet(
                                        "A0002",
                                        "T002")),
                        List.of());

        assertTrue(whileOutstanding.isEmpty());
        assertSame(
                packet1,
                simulator.outstandingPacket(NODE_A));

        List<UpstreamData> afterAck =
                simulator.accept(
                        receivePacket(3L, 1L),
                        RECEIVED.plusSeconds(2));

        assertEquals(1, afterAck.size());
        UpstreamData packet2 = afterAck.get(0);
        assertEquals(2L, packet2.packetNr());
        UpstreamData.RaceData delta =
                (UpstreamData.RaceData) packet2.message();
        assertEquals(1, delta.teamAssignmentChanges().size());
        assertEquals(
                "A0002",
                delta.teamAssignmentChanges()
                        .get(0)
                        .registrationId()
                        .value());

        CentralSystemSimulator.NodeView view =
                simulator.nodes().get(0);
        assertEquals(1L, view.lastDnPacketAcknowledged());
        assertEquals(Long.valueOf(2L), view.outstandingDnPacketNr());
    }

    @Test
    void recvOfLastValidPacketResendsExactOutstandingPacket() {
        CentralSystemSimulator simulator =
                openSimulatorAtLocation(7);

        UpstreamData first =
                simulator.updateRaceData(
                        NODE_A,
                        List.of(
                                DefaultUpstreamData.createTeamAssignmentSet(
                                        "A0001",
                                        "T001")),
                        List.of())
                        .get(0);
        simulator.updateRaceData(
                NODE_A,
                List.of(
                        DefaultUpstreamData.createTeamAssignmentSet(
                                "A0002",
                                "T002")),
                List.of());

        UpstreamData second =
                simulator.accept(
                        receivePacket(10L, first.packetNr()),
                        RECEIVED.plusSeconds(1))
                        .get(0);

        List<UpstreamData> resend =
                simulator.accept(
                        receivePacket(11L, first.packetNr()),
                        RECEIVED.plusSeconds(2));

        assertEquals(1, resend.size());
        assertSame(second, resend.get(0));
    }

    @Test
    void reconnectRetriesExactOutstandingPacketWithoutResettingPacketState() {
        CentralSystemSimulator simulator =
                openSimulatorAtLocation(7);

        UpstreamData outstanding =
                simulator.updateRaceData(
                        NODE_A,
                        List.of(
                                DefaultUpstreamData.createTeamAssignmentSet(
                                        "A0001",
                                        "T001")),
                        List.of())
                        .get(0);

        simulator.disconnected();
        simulator.connected();

        List<UpstreamData> retry =
                simulator.accept(
                        upPacket(
                                7L,
                                DefaultUpstreamData.createSequenceNotification(1L)),
                        RECEIVED.plusSeconds(1));

        assertEquals(1, retry.size());
        assertSame(outstanding, retry.get(0));
        assertEquals(1L, retry.get(0).packetNr());
        assertEquals(
                Long.valueOf(1L),
                simulator.nodes().get(0).outstandingDnPacketNr());
    }

    @Test
    void recvZeroRebuildsCurrentRaceStateFromPacketOne() {
        CentralSystemSimulator simulator =
                openSimulatorAtLocation(7);

        UpstreamData first =
                simulator.updateRaceData(
                        NODE_A,
                        List.of(
                                DefaultUpstreamData.createTeamAssignmentSet(
                                        "A0001",
                                        "T001")),
                        List.of())
                        .get(0);
        simulator.accept(
                receivePacket(4L, first.packetNr()),
                RECEIVED.plusSeconds(1));

        List<UpstreamData> rebuilt =
                simulator.accept(
                        receivePacket(5L, 0L),
                        RECEIVED.plusSeconds(2));

        assertEquals(1, rebuilt.size());
        assertEquals(1L, rebuilt.get(0).packetNr());
        UpstreamData.RaceData raceData =
                (UpstreamData.RaceData) rebuilt.get(0).message();
        assertEquals(1, raceData.teamAssignmentChanges().size());
        assertEquals(
                "A0001",
                raceData.teamAssignmentChanges()
                        .get(0)
                        .registrationId()
                        .value());
    }

    @Test
    void pingAnswerUsesPacketZeroAndCentralMessageTime() {
        Instant answerTime =
                Instant.parse("2026-10-24T14:13:00Z");
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(
                        List.of(),
                        Clock.fixed(answerTime, ZoneOffset.UTC));
        simulator.connected();

        UpstreamData request =
                DefaultUpstreamData.createUpPacket(
                        NODE_A,
                        0L,
                        DefaultUpstreamData.createPing(
                                UpstreamData.PingOperation.REQ,
                                UpstreamData.PingMode.HB,
                                "hb-1",
                                new TimingTimestamp(RECEIVED)));

        UpstreamData answerPacket =
                simulator.accept(
                        request,
                        RECEIVED.plusMillis(10))
                        .get(0);

        assertEquals(0L, answerPacket.packetNr());
        UpstreamData.Ping answer =
                (UpstreamData.Ping) answerPacket.message();
        assertEquals(answerTime, answer.time().instant());
        assertEquals("hb-1", answer.pingId());
        assertSame(UpstreamData.PingMode.HB, answer.mode());
    }

    @Test
    void semanticMessageJournalSeparatesMessageAndReceiveTimes() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of());
        Instant inboundMessageTime = RECEIVED.minusMillis(40);
        Instant inboundEventTime = RECEIVED.minusMillis(10);
        Instant inboundReceivedAt = RECEIVED;

        UpstreamData inbound =
                DefaultUpstreamData.createUpPacket(
                        NODE_A,
                        0L,
                        DefaultUpstreamData.createPing(
                                UpstreamData.PingOperation.REQ,
                                UpstreamData.PingMode.HB,
                                "hb-journal",
                                new TimingTimestamp(inboundMessageTime)));
        UpstreamData outbound =
                DefaultUpstreamData.createDnPacket(
                        NODE_A,
                        0L,
                        DefaultUpstreamData.createPing(
                                UpstreamData.PingOperation.ANS,
                                UpstreamData.PingMode.HB,
                                "hb-journal",
                                new TimingTimestamp(
                                        RECEIVED.plusMillis(2))));

        String inboundRaw =
                CentralUpstreamJson.encode(inbound);
        String outboundRaw =
                CentralUpstreamJson.encode(outbound);

        simulator.accept(
                inbound,
                inboundEventTime,
                inboundReceivedAt,
                inboundRaw);
        simulator.recordSent(
                outbound,
                RECEIVED.plusMillis(5),
                outboundRaw);

        List<CentralSystemSimulator.MessageView> messages =
                simulator.messages();
        assertEquals(2, messages.size());
        assertEquals(
                inboundMessageTime,
                messages.get(0).messageTime());
        assertEquals(
                inboundReceivedAt,
                messages.get(0).receivedAt());
        assertEquals(
                RECEIVED.plusMillis(2),
                messages.get(1).messageTime());
        assertNull(messages.get(1).receivedAt());
    }

    @Test
    void timingDataMessageTimeUsesLastEffectiveTimeInsteadOfEventReceiptTime() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(7));
        simulator.connected();

        Instant firstEffective =
                Instant.parse("2026-10-24T17:31:55.490Z");
        Instant secondEffective =
                Instant.parse("2026-10-24T17:31:55.492Z");
        TimingRecord first =
                TimingRecord.createNodeOpened(
                        'A',
                        1L,
                        7,
                        firstEffective.toEpochMilli(),
                        Instant.parse("2026-10-24T17:31:55.500Z").toEpochMilli());
        TimingRecord second =
                TimingRecord.createNodeClosed(
                        'A',
                        2L,
                        7,
                        secondEffective.toEpochMilli(),
                        Instant.parse("2026-10-24T17:31:55.501Z").toEpochMilli());

        UpstreamData packet =
                timingDataPacket(
                        1L,
                        first,
                        second);
        Instant eventTime =
                Instant.parse("2026-10-24T17:31:55.505Z");
        Instant receivedAt =
                Instant.parse("2026-10-24T17:31:55.506Z");

        simulator.accept(
                packet,
                eventTime,
                receivedAt,
                CentralUpstreamJson.encode(packet));

        CentralSystemSimulator.MessageView message =
                simulator.messages().get(0);
        assertEquals(secondEffective, message.messageTime());
        assertEquals(receivedAt, message.receivedAt());
    }

    @Test
    void semanticMessageJournalKeepsLatestTenThousandModelRecords() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of());

        for (int index = 1; index <= 10_001; index++) {
            UpstreamData outbound =
                    DefaultUpstreamData.createDnPacket(
                            NODE_A,
                            0L,
                            DefaultUpstreamData.createPing(
                                    UpstreamData.PingOperation.ANS,
                                    UpstreamData.PingMode.HB,
                                    "hb-" + index,
                                    new TimingTimestamp(RECEIVED.plusMillis(index))));
            simulator.recordSent(
                    outbound,
                    RECEIVED.plusMillis(index),
                    CentralUpstreamJson.encode(outbound));
        }

        List<CentralSystemSimulator.MessageView> messages =
                simulator.messages();
        assertEquals(10_000, messages.size());
        assertEquals(2L, messages.get(0).ordinal());
        assertEquals(10_001L, messages.get(messages.size() - 1).ordinal());
    }

    @Test
    void fullResetClearsCentralOwnedSourceState() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(1, 2));
        simulator.updateStageStarts(
                2,
                List.of(
                        DefaultUpstreamData.createStageStartSet(
                                "A0001",
                                TimingTimestamp.parse("10:00:00"))));

        assertFalse(simulator.stages().isEmpty());

        simulator.resetTargetState();
        assertFalse(
                simulator.stages().isEmpty(),
                "target reset retains Central-owned source state");

        simulator.resetAllState();
        assertTrue(
                simulator.stages().stream()
                        .allMatch(stage -> stage.startTimes().isEmpty()),
                "full reset clears all authored StageStartTimes");
        assertTrue(simulator.timingRecords().isEmpty());
        assertFalse(simulator.isConnected());
    }

    @Test
    void reconstructedLogBookIgnoresReplayDuplicatesAndSurvivesReconnect() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(7));
        simulator.connected();

        UpstreamData firstBatch =
                DefaultUpstreamData.createUpPacket(
                        NODE_A,
                        1L,
                        DefaultUpstreamData.createTimingData(
                                List.of(
                                        opened(1L, 7),
                                        closed(2L, 7))));

        simulator.accept(firstBatch, RECEIVED);
        simulator.accept(
                firstBatch,
                RECEIVED.plusSeconds(1));

        assertEquals(2, simulator.timingRecords().size());

        simulator.disconnected();
        simulator.connected();
        assertEquals(2, simulator.timingRecords().size());

        simulator.resetTargetState();
        assertTrue(simulator.timingRecords().isEmpty());
    }

    @Test
    void manualTimingDataRequestUsesNormalDownstreamPacket() {
        CentralSystemSimulator simulator =
                openSimulatorAtLocation(7);

        List<UpstreamData> packets =
                simulator.requestTimingData(
                        NODE_A,
                        1L);

        assertEquals(1, packets.size());
        UpstreamData packet = packets.get(0);
        assertEquals(1L, packet.packetNr());
        assertEquals(UpstreamData.Family.TIMING_DATA_REQ, packet.family());
        assertEquals(
                1L,
                ((UpstreamData.TimingDataRequest) packet.message())
                        .fromSequence());
    }

    @Test
    void stageStartUpdateUsesCurrentRaceDataPacket() {
        CentralSystemSimulator simulator =
                openSimulatorAtLocation(7);

        List<UpstreamData> packets =
                simulator.updateRaceData(
                        NODE_A,
                        List.of(),
                        List.of(
                                DefaultUpstreamData.createStageStartSet(
                                        "A0001",
                                        TimingTimestamp.parse("10:30:00.25"))));

        UpstreamData.RaceData raceData =
                (UpstreamData.RaceData) packets.get(0).message();
        assertEquals(1, raceData.stageStartTimeChanges().size());
        assertEquals(
                TimingTimestamp.parse("10:30:00.25"),
                raceData.stageStartTimeChanges().get(0).start());
    }

    @Test
    void raceDataUpdatesRequireConnectedCentralAndOpenLocation() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(7));

        assertThrows(
                IllegalStateException.class,
                () -> simulator.updateRaceData(
                        NODE_A,
                        List.of(
                                DefaultUpstreamData.createTeamAssignmentSet(
                                        "A0001",
                                        "T001")),
                        List.of()));

        simulator.connected();
        assertThrows(
                IllegalStateException.class,
                () -> simulator.updateRaceData(
                        NODE_A,
                        List.of(
                                DefaultUpstreamData.createTeamAssignmentSet(
                                        "A0001",
                                        "T001")),
                        List.of()));
    }

    @Test
    void heartbeatCreatesNodeWithoutInventingLifecycleState() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of());
        simulator.connected();

        simulator.accept(
                DefaultUpstreamData.createUpPacket(
                        NODE_A,
                        0L,
                        DefaultUpstreamData.createPing(
                                UpstreamData.PingOperation.REQ,
                                UpstreamData.PingMode.HB,
                                "hb-unknown")),
                RECEIVED);

        CentralSystemSimulator.NodeView node =
                simulator.nodes().get(0);
        assertEquals("UNKNOWN", node.lifecycleState());
        assertNull(node.locationId());
        assertEquals(RECEIVED, node.lastHeartbeat());
    }

    @Test
    void runtimeOpenMoveClosesPreviousLocationForSameNode() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(1, 2, 3));
        simulator.connected();

        simulator.applyRuntimeStatus(
                "A",
                "OPEN",
                Integer.valueOf(1),
                RECEIVED);
        simulator.applyRuntimeStatus(
                "A",
                "OPEN",
                Integer.valueOf(2),
                RECEIVED.plusSeconds(1));
        simulator.applyRuntimeStatus(
                "A",
                "OPEN",
                Integer.valueOf(3),
                RECEIVED.plusSeconds(2));

        assertEquals(
                "CLOSED",
                location(simulator, 1).state());
        assertEquals(
                "CLOSED",
                location(simulator, 2).state());
        assertEquals(
                "OPEN",
                location(simulator, 3).state());
        assertEquals(
                Integer.valueOf(3),
                simulator.nodes().get(0).locationId());
    }

    @Test
    void targetResetClearsLearnedStateButKeepsConfiguredLocations() {
        CentralSystemSimulator simulator =
                openSimulatorAtLocation(7);
        simulator.updateRaceData(
                NODE_A,
                List.of(
                        DefaultUpstreamData.createTeamAssignmentSet(
                                "A0001",
                                "T001")),
                List.of());

        simulator.resetTargetState();

        assertTrue(simulator.nodes().isEmpty());
        assertEquals(
                "UNKNOWN",
                location(simulator, 7).state());
        assertFalse(simulator.isConnected());
    }

    private static CentralSystemSimulator openSimulatorAtLocation(
            int locationId) {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(locationId));
        simulator.connected();
        simulator.accept(
                timingDataPacket(
                        1L,
                        opened(1L, locationId)),
                RECEIVED);
        simulator.accept(
                upPacket(
                        2L,
                        DefaultUpstreamData.createSequenceNotification(1L)),
                RECEIVED.plusMillis(1L));
        simulator.applyRuntimeStatus(
                "A",
                "OPEN",
                Integer.valueOf(locationId),
                RECEIVED.plusMillis(2L));
        return simulator;
    }

    private static UpstreamData timingDataPacket(
            long packetNr,
            TimingRecord... records) {
        return DefaultUpstreamData.createUpPacket(
                NODE_A,
                packetNr,
                DefaultUpstreamData.createTimingData(
                        List.of(records)));
    }

    private static UpstreamData upPacket(
            long packetNr,
            UpstreamData.Message message) {
        return DefaultUpstreamData.createUpPacket(
                NODE_A,
                packetNr,
                message);
    }

    private static UpstreamData receivePacket(
            long upPacketNr,
            long receivedPacketNr) {
        return upPacket(
                upPacketNr,
                DefaultUpstreamData.createReceive(
                        receivedPacketNr));
    }

    private static TimingRecord opened(
            long sequence,
            int locationId) {
        return TimingRecord.createNodeOpened(
                'A',
                sequence,
                locationId,
                RECEIVED.toEpochMilli(),
                RECEIVED.toEpochMilli());
    }

    private static TimingRecord closed(
            long sequence,
            int locationId) {
        return TimingRecord.createNodeClosed(
                'A',
                sequence,
                locationId,
                RECEIVED.toEpochMilli(),
                RECEIVED.toEpochMilli());
    }

    private static CentralSystemSimulator.LocationView location(
            CentralSystemSimulator simulator,
            int locationId) {
        return simulator.locations()
                .stream()
                .filter(value -> value.locationId() == locationId)
                .findFirst()
                .orElseThrow();
    }
}
