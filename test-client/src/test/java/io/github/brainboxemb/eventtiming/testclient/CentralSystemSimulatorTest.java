package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
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
    void timingDataSyncAdvertisesRaceDataHeadAndRequestsMissingTimingSuffix() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7));
        simulator.connected();

        List<UpstreamData> replies = simulator.accept(
                DefaultUpstreamData.sync(
                        NODE_A,
                        UpstreamData.Stream.TIMING_DATA,
                        4L),
                RECEIVED);

        assertEquals(2, replies.size());

        UpstreamData.Sync raceSync =
                (UpstreamData.Sync) replies.get(0);
        assertSame(UpstreamData.Stream.RACE_DATA, raceSync.stream());
        assertEquals(0L, raceSync.sequence());

        UpstreamData.Req timingRequest =
                (UpstreamData.Req) replies.get(1);
        assertSame(UpstreamData.Stream.TIMING_DATA, timingRequest.stream());
        assertEquals(1L, timingRequest.fromSequence());
        assertEquals(NODE_A, timingRequest.timingNodeId());
    }

    @Test
    void timingDataSyncRequestsHandoffEvenWhenCentralIsAlreadyCaughtUp() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7));
        simulator.connected();
        simulator.accept(
                timingData(opened(1L, 7)),
                RECEIVED);

        List<UpstreamData> replies = simulator.accept(
                DefaultUpstreamData.sync(
                        NODE_A,
                        UpstreamData.Stream.TIMING_DATA,
                        1L),
                RECEIVED.plusSeconds(1));

        assertEquals(2, replies.size());
        UpstreamData.Req request =
                (UpstreamData.Req) replies.get(1);
        assertSame(UpstreamData.Stream.TIMING_DATA, request.stream());
        assertEquals(2L, request.fromSequence());
    }

    @Test
    void semanticMessageJournalTracksInboundAndOutboundDirections() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of());
        UpstreamData.Ping inbound =
                DefaultUpstreamData.ping(
                        NODE_A,
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        "hb-journal");
        UpstreamData.Ping outbound =
                DefaultUpstreamData.ping(
                        NODE_A,
                        UpstreamData.PingOperation.ANS,
                        UpstreamData.PingMode.HB,
                        "hb-journal");

        simulator.accept(inbound, RECEIVED);
        simulator.recordSent(outbound, RECEIVED.plusMillis(5));

        List<CentralSystemSimulator.MessageView> messages = simulator.messages();
        assertEquals(2, messages.size());
        assertSame(
                CentralSystemSimulator.MessageDirection.IN,
                messages.get(0).direction());
        assertSame(inbound, messages.get(0).data());
        assertSame(
                CentralSystemSimulator.MessageDirection.OUT,
                messages.get(1).direction());
        assertSame(outbound, messages.get(1).data());
        assertEquals(1L, messages.get(0).ordinal());
        assertEquals(2L, messages.get(1).ordinal());
    }

    @Test
    void messageJournalIsBoundedButRaceDataReplayHistoryIsIndependent() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of());
        simulator.connected();
        UpstreamData.RaceData retained =
                createRaceData(simulator, "A0001", "T001");

        for (int i = 0; i < 2100; i++) {
            simulator.recordSent(
                    DefaultUpstreamData.ping(
                            NODE_A,
                            UpstreamData.PingOperation.ANS,
                            UpstreamData.PingMode.HB,
                            "hb-" + i),
                    RECEIVED.plusMillis(i));
        }

        assertEquals(2000, simulator.messages().size());

        List<UpstreamData> replay = simulator.accept(
                DefaultUpstreamData.req(
                        NODE_A,
                        UpstreamData.Stream.RACE_DATA,
                        1L),
                RECEIVED.plusSeconds(10));

        assertEquals(1, replay.size());
        assertSame(retained, replay.get(0));
    }

    @Test
    void reconnectKeepsMessageJournalButTargetResetClearsIt() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of());
        simulator.accept(
                DefaultUpstreamData.ping(
                        NODE_A,
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        "hb-1"),
                RECEIVED);

        simulator.disconnected();
        simulator.connected();
        assertEquals(1, simulator.messages().size());

        simulator.resetTargetState();
        assertTrue(simulator.messages().isEmpty());
    }

    @Test
    void reconnectPreservesBothProtocolHeads() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7));
        simulator.connected();
        simulator.accept(
                timingData(opened(1L, 7)),
                RECEIVED);
        UpstreamData.RaceData raceData = createRaceData(simulator, "A0001", "T001");
        assertEquals(1L, raceData.sequence());

        simulator.disconnected();
        simulator.connected();

        List<UpstreamData> replies = simulator.accept(
                DefaultUpstreamData.sync(
                        NODE_A,
                        UpstreamData.Stream.TIMING_DATA,
                        4L),
                RECEIVED.plusSeconds(1));

        UpstreamData.Sync raceSync =
                (UpstreamData.Sync) replies.get(0);
        assertEquals(1L, raceSync.sequence());

        UpstreamData.Req timingRequest =
                (UpstreamData.Req) replies.get(1);
        assertEquals(2L, timingRequest.fromSequence());
    }

    @Test
    void rejectsRaceDataCreationWhileCentralDisconnected() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of());

        assertThrows(
                IllegalStateException.class,
                () -> simulator.createRaceData(
                        NODE_A,
                        List.of(
                                DefaultUpstreamData.teamAssignmentSet(
                                        "A0001",
                                        "T001")),
                        List.of()));
        assertTrue(simulator.nodes().isEmpty());

        simulator.connected();
        assertEquals(
                1L,
                createRaceData(simulator, "A0001", "T001").sequence());
    }

    @Test
    void rejectsRaceDataCreationWithoutOpenLocation() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7));
        simulator.connected();

        assertThrows(
                IllegalStateException.class,
                () -> simulator.createRaceData(
                        NODE_A,
                        List.of(
                                DefaultUpstreamData.teamAssignmentSet(
                                        "A0001",
                                        "T001")),
                        List.of()));

        simulator.accept(timingData(opened(1L, 7)), RECEIVED);
        simulator.accept(timingData(closed(2L, 7)), RECEIVED.plusSeconds(1));

        assertThrows(
                IllegalStateException.class,
                () -> simulator.createRaceData(
                        NODE_A,
                        List.of(
                                DefaultUpstreamData.teamAssignmentSet(
                                        "A0001",
                                        "T001")),
                        List.of()));
    }

    @Test
    void createsOrderedRaceDataAndReplaysOriginalValues() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of());
        simulator.connected();

        UpstreamData.RaceData first =
                createRaceData(simulator, "A0001", "T001");
        UpstreamData.RaceData second =
                createRaceData(simulator, "A0002", "T002");

        assertEquals(1, first.locationId().value());
        assertEquals(1, second.locationId().value());
        assertEquals(1L, first.sequence());
        assertEquals(2L, second.sequence());
        assertEquals(2L, simulator.nodes().get(0).raceDataHead());

        List<UpstreamData> replay = simulator.accept(
                DefaultUpstreamData.req(
                        NODE_A,
                        UpstreamData.Stream.RACE_DATA,
                        1L),
                RECEIVED);

        assertEquals(2, replay.size());
        assertSame(first, replay.get(0));
        assertSame(second, replay.get(1));
    }

    @Test
    void createsTeamAssignmentDeleteAsNextRaceDataSequence() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of());
        simulator.connected();
        createRaceData(simulator, "A0001", "T001");

        UpstreamData.RaceData delete = simulator.createRaceData(
                NODE_A,
                List.of(DefaultUpstreamData.teamAssignmentDelete("A0001")),
                List.of());

        assertEquals(2L, delete.sequence());
        assertEquals(1, delete.teamAssignmentChanges().size());
        UpstreamData.TeamAssignmentChange change =
                delete.teamAssignmentChanges().get(0);
        assertSame(UpstreamData.ChangeOperation.DEL, change.operation());
        assertEquals("A0001", change.registrationId().value());
        assertNull(change.teamId());
    }

    @Test
    void consumesCumulativeRaceDataAckWithoutDroppingReplayHistory() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of());
        simulator.connected();
        UpstreamData.RaceData first =
                createRaceData(simulator, "A0001", "T001");
        UpstreamData.RaceData second =
                createRaceData(simulator, "A0002", "T002");

        simulator.accept(
                DefaultUpstreamData.ack(
                        NODE_A,
                        UpstreamData.Stream.RACE_DATA,
                        2L),
                RECEIVED);

        assertEquals(2L, simulator.nodes().get(0).raceDataAcknowledged());

        List<UpstreamData> replay = simulator.accept(
                DefaultUpstreamData.req(
                        NODE_A,
                        UpstreamData.Stream.RACE_DATA,
                        1L),
                RECEIVED.plusSeconds(1));
        assertSame(first, replay.get(0));
        assertSame(second, replay.get(1));

        simulator.accept(
                DefaultUpstreamData.ack(
                        NODE_A,
                        UpstreamData.Stream.RACE_DATA,
                        1L),
                RECEIVED.plusSeconds(2));
        assertEquals(2L, simulator.nodes().get(0).raceDataAcknowledged());
    }

    @Test
    void rejectsAckBeyondCentralRaceDataSourceHead() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of());
        simulator.connected();
        createRaceData(simulator, "A0001", "T001");

        assertThrows(
                IllegalStateException.class,
                () -> simulator.accept(
                        DefaultUpstreamData.ack(
                                NODE_A,
                                UpstreamData.Stream.RACE_DATA,
                                2L),
                        RECEIVED));
    }

    @Test
    void keepsNodeLifecycleUnknownUntilLifecycleRecordArrives() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of());

        simulator.accept(
                DefaultUpstreamData.ping(
                        NODE_A,
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        "hb-unknown"),
                RECEIVED);

        assertEquals("UNKNOWN", simulator.nodes().get(0).lifecycleState());
        assertNull(simulator.nodes().get(0).locationId());
    }

    @Test
    void heartbeatIsVisibleAndAnsweredWithCorrelation() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of());

        UpstreamData.Ping answer =
                (UpstreamData.Ping) simulator.accept(
                        DefaultUpstreamData.ping(
                                NODE_A,
                                UpstreamData.PingOperation.REQ,
                                UpstreamData.PingMode.HB,
                                "hb-3"),
                        RECEIVED)
                        .get(0);

        assertSame(UpstreamData.PingOperation.ANS, answer.operation());
        assertSame(UpstreamData.PingMode.HB, answer.mode());
        assertEquals("hb-3", answer.pingId());
        assertEquals(RECEIVED, simulator.nodes().get(0).lastHeartbeat());
    }

    @Test
    void targetChangeClearsLearnedAndRaceDataStateButKeepsConfiguredLocations() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7, 8));
        simulator.connected();
        simulator.accept(
                timingData(opened(1L, 7)),
                RECEIVED);
        createRaceData(simulator, "A0001", "T001");

        simulator.resetTargetState();

        assertTrue(simulator.nodes().isEmpty());
        assertEquals("UNKNOWN", location(simulator, 7).state());
        assertEquals("UNKNOWN", location(simulator, 8).state());
        assertNull(location(simulator, 7).nodeId());
        assertFalse(simulator.isConnected());
    }

    @Test
    void newOpenImplicitlyClosesPreviousLocationForSameNode() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(1, 2, 3));

        simulator.accept(
                timingData(opened(1L, 1)),
                RECEIVED);
        simulator.accept(
                timingData(opened(2L, 2)),
                RECEIVED.plusSeconds(1));
        simulator.accept(
                timingData(opened(3L, 3)),
                RECEIVED.plusSeconds(2));

        assertEquals("CLOSED", location(simulator, 1).state());
        assertEquals(2L, location(simulator, 1).sequence());
        assertEquals(RECEIVED.plusSeconds(1), location(simulator, 1).changedAt());

        assertEquals("CLOSED", location(simulator, 2).state());
        assertEquals(3L, location(simulator, 2).sequence());
        assertEquals(RECEIVED.plusSeconds(2), location(simulator, 2).changedAt());

        assertEquals("OPEN", location(simulator, 3).state());
        assertEquals(3L, location(simulator, 3).sequence());
        assertEquals("A", location(simulator, 3).nodeId());

        CentralSystemSimulator.NodeView system =
                simulator.nodes().get(0);
        assertEquals("OPEN", system.lifecycleState());
        assertEquals(Integer.valueOf(3), system.locationId());
    }

    @Test
    void repeatedOpenForSameLocationDoesNotCloseIt() {
        CentralSystemSimulator simulator =
                new CentralSystemSimulator(List.of(1));

        simulator.accept(
                timingData(opened(1L, 1)),
                RECEIVED);
        simulator.accept(
                timingData(opened(2L, 1)),
                RECEIVED.plusSeconds(1));

        assertEquals("OPEN", location(simulator, 1).state());
        assertEquals(2L, location(simulator, 1).sequence());
        assertEquals(RECEIVED.plusSeconds(1), location(simulator, 1).changedAt());
        assertEquals(Integer.valueOf(1), simulator.nodes().get(0).locationId());
    }

    @Test
    void derivesOpenAndClosedLocationFromReceivedLogBookRecords() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7, 8));

        simulator.accept(
                timingData(opened(1L, 7)),
                RECEIVED);
        assertEquals("OPEN", location(simulator, 7).state());
        assertEquals("A", location(simulator, 7).nodeId());
        assertEquals("OPEN", simulator.nodes().get(0).lifecycleState());
        assertEquals(Integer.valueOf(7), simulator.nodes().get(0).locationId());

        simulator.accept(
                timingData(closed(2L, 7)),
                RECEIVED.plusSeconds(1));

        assertEquals("CLOSED", location(simulator, 7).state());
        assertEquals("CLOSED", simulator.nodes().get(0).lifecycleState());
        assertNull(simulator.nodes().get(0).locationId());
        assertEquals(2L, simulator.nodes().get(0).timingDataHead());
        assertSame(
                TimingRecord.Kind.NODE_CLOSE,
                simulator.nodes().get(0).lastRecord().kind());
        assertEquals("UNKNOWN", location(simulator, 8).state());
    }

    private static UpstreamData.RaceData createRaceData(
            CentralSystemSimulator simulator,
            String registrationId,
            String teamId) {
        CentralSystemSimulator.NodeView node =
                simulator.nodes().stream()
                        .filter(value -> "A".equals(value.nodeId()))
                        .findFirst()
                        .orElse(null);
        if (node == null || node.locationId() == null) {
            long nextSequence = node == null ? 1L : node.timingDataHead() + 1L;
            simulator.accept(
                    timingData(opened(nextSequence, 1)),
                    RECEIVED.plusMillis(nextSequence));
        }

        return simulator.createRaceData(
                NODE_A,
                List.of(
                        DefaultUpstreamData.teamAssignmentSet(
                                registrationId,
                                teamId)),
                List.of());
    }

    private static CentralSystemSimulator.LocationView location(
            CentralSystemSimulator simulator,
            int locationId) {
        return simulator.locations().stream()
                .filter(value -> value.locationId() == locationId)
                .findFirst()
                .orElseThrow();
    }

    private static UpstreamData.TimingDataTransfer timingData(TimingRecord record) {
        return DefaultUpstreamData.timingData(NODE_A, List.of(record));
    }

    private static TimingRecord opened(long sequence, int locationId) {
        return TimingRecord.createNodeOpened(
                'A',
                sequence,
                locationId,
                RECEIVED.toEpochMilli(),
                RECEIVED.toEpochMilli());
    }

    private static TimingRecord closed(long sequence, int locationId) {
        return TimingRecord.createNodeClosed(
                'A',
                sequence,
                locationId,
                RECEIVED.toEpochMilli(),
                RECEIVED.toEpochMilli());
    }
}
