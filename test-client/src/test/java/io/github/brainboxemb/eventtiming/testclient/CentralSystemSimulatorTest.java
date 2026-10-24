package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CentralSystemSimulatorTest {
    private static final Instant RECEIVED =
            Instant.parse("2026-10-24T08:00:00Z");
    private static final NodeId NODE_A = new NodeId("A");

    @Test
    void requestsMissingTimingDataFromItsOwnNextSequence() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7));
        simulator.connected();

        List<UpstreamData> replies = simulator.accept(
                DefaultUpstreamData.sync(
                        NODE_A,
                        UpstreamData.Stream.TIMING_DATA,
                        4L),
                RECEIVED);

        UpstreamData.Req request =
                (UpstreamData.Req) replies.get(0);
        assertSame(UpstreamData.Stream.TIMING_DATA, request.stream());
        assertEquals(1L, request.fromSequence());
        assertEquals(NODE_A, request.timingNodeId());
    }

    @Test
    void reconnectRequestsOnlyTheMissingTimingDataSuffix() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7));
        simulator.accept(
                timingData(opened(1L, 7)),
                RECEIVED);
        simulator.disconnected();
        simulator.connected();

        UpstreamData.Req request =
                (UpstreamData.Req) simulator.accept(
                        DefaultUpstreamData.sync(
                                NODE_A,
                                UpstreamData.Stream.TIMING_DATA,
                                4L),
                        RECEIVED.plusSeconds(1))
                        .get(0);

        assertEquals(2L, request.fromSequence());
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
        assertEquals(RECEIVED, simulator.systems().get(0).lastHeartbeat());
    }

    @Test
    void targetChangeClearsLearnedStateButKeepsConfiguredLocations() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7, 8));
        simulator.connected();
        simulator.accept(
                timingData(opened(1L, 7)),
                RECEIVED);

        simulator.resetTargetState();

        assertTrue(simulator.systems().isEmpty());
        assertEquals("UNKNOWN", location(simulator, 7).state());
        assertEquals("UNKNOWN", location(simulator, 8).state());
        assertNull(location(simulator, 7).nodeId());
        assertEquals(false, simulator.isConnected());
    }

    @Test
    void derivesOpenAndClosedLocationFromReceivedLogBookRecords() {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7, 8));

        simulator.accept(
                timingData(opened(1L, 7)),
                RECEIVED);
        assertEquals("OPEN", location(simulator, 7).state());
        assertEquals("A", location(simulator, 7).nodeId());
        assertEquals(Integer.valueOf(7), simulator.systems().get(0).locationId());

        simulator.accept(
                timingData(closed(2L, 7)),
                RECEIVED.plusSeconds(1));

        assertEquals("CLOSED", location(simulator, 7).state());
        assertNull(simulator.systems().get(0).locationId());
        assertEquals(2L, simulator.systems().get(0).timingDataHead());
        assertSame(
                TimingRecord.Kind.NODE_CLOSE,
                simulator.systems().get(0).lastRecord().kind());
        assertEquals("UNKNOWN", location(simulator, 8).state());
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
