package io.github.brainboxemb.eventtiming.testclient;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CentralSystemSimulatorTest {
    private static final Instant RECEIVED =
            Instant.parse("2026-10-24T08:00:00Z");

    @Test
    void requestsMissingTimingDataFromItsOwnNextSequence() throws Exception {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7));
        simulator.connected();

        List<String> replies = simulator.accept(
                "{\"v\":1,\"type\":\"SYNC\",\"nodeId\":\"A\","
                        + "\"data\":{\"stream\":\"TIMING_DATA\",\"seq\":4}}",
                RECEIVED);

        assertEquals(1, replies.size());
        assertEquals(
                "{\"v\":1,\"type\":\"REQ\",\"nodeId\":\"A\","
                        + "\"data\":{\"stream\":\"TIMING_DATA\",\"fromSeq\":1}}",
                replies.get(0));
    }

    @Test
    void heartbeatIsVisibleAndAnsweredWithCorrelation() throws Exception {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of());

        List<String> replies = simulator.accept(
                "{\"v\":1,\"type\":\"PING\",\"nodeId\":\"A\","
                        + "\"data\":{\"op\":\"REQ\",\"mode\":\"HB\","
                        + "\"pingId\":\"hb-3\"}}",
                RECEIVED);

        assertEquals(
                "{\"v\":1,\"type\":\"PING\",\"nodeId\":\"A\","
                        + "\"data\":{\"op\":\"ANS\",\"mode\":\"HB\","
                        + "\"pingId\":\"hb-3\"}}",
                replies.get(0));
        assertEquals(RECEIVED, simulator.systems().get(0).lastHeartbeat());
    }

    @Test
    void targetChangeClearsLearnedStateButKeepsConfiguredLocations() throws Exception {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7, 8));
        simulator.connected();
        simulator.accept(
                timingData(1, 7, "OPEN"),
                RECEIVED);

        simulator.resetTargetState();

        assertTrue(simulator.systems().isEmpty());
        assertEquals("UNKNOWN", location(simulator, 7).state());
        assertEquals("UNKNOWN", location(simulator, 8).state());
        assertNull(location(simulator, 7).nodeId());
        assertEquals(false, simulator.isConnected());
    }

    @Test
    void derivesOpenAndClosedLocationFromReceivedLogBookRecords() throws Exception {
        CentralSystemSimulator simulator = new CentralSystemSimulator(List.of(7, 8));

        simulator.accept(
                timingData(1, 7, "OPEN"),
                RECEIVED);
        assertEquals("OPEN", location(simulator, 7).state());
        assertEquals("A", location(simulator, 7).nodeId());
        assertEquals(Integer.valueOf(7), simulator.systems().get(0).locationId());

        simulator.accept(
                timingData(2, 7, "CLOSE"),
                RECEIVED.plusSeconds(1));

        assertEquals("CLOSED", location(simulator, 7).state());
        assertNull(simulator.systems().get(0).locationId());
        assertEquals(2L, simulator.systems().get(0).timingDataHead());
        assertTrue(simulator.systems().get(0).lastRecord().rawJson().contains("\"CLOSE\""));
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

    private static String timingData(long sequence, int locationId, String code) {
        return "{"
                + "\"v\":1,"
                + "\"type\":\"TIMING_DATA\","
                + "\"nodeId\":\"A\","
                + "\"data\":[{"
                + "\"v\":1,"
                + "\"nodeId\":\"A\","
                + "\"seqNr\":" + sequence + ","
                + "\"locId\":" + locationId + ","
                + "\"recType\":\"NODE_INFO\","
                + "\"time\":\"2026-10-24T08:00:00Z\","
                + "\"code\":[\"" + code + "\"],"
                + "\"recTime\":\"2026-10-24T08:00:00.000Z\""
                + "}]}";
    }
}
