package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiEventClientTest {
    @Test
    void classifiesUpstreamRoleOnlyFromExplicitRoleParameter() {
        assertEquals(
                false,
                ApiEventClient.isUpstreamRole(
                        java.net.URI.create(
                                "ws://127.0.0.1:8082/api/v1/events")));
        assertEquals(
                true,
                ApiEventClient.isUpstreamRole(
                        java.net.URI.create(
                                "ws://127.0.0.1:8082/api/v1/events?role=upstream")));
    }

    @Test
    void parsesCompactStatusSnapshot() throws Exception {
        String json = "{"
                + "\"eventType\":\"STATUS_SNAPSHOT\","
                + "\"occurredAt\":\"2026-10-01T12:00:02Z\","
                + "\"payload\":{"
                + "\"nodes\":[{"
                + "\"id\":\"TN-01\","
                + "\"locId\":24,"
                + "\"state\":\"OPEN\""
                + "}]"
                + "}"
                + "}";

        ApiEventClient.ApiEvent parsed = ApiEventClient.parseEvent(json);
        var event = assertInstanceOf(ApiEventClient.StatusEvent.class, parsed);

        assertEquals("STATUS_SNAPSHOT", event.eventType());
        assertEquals(Instant.parse("2026-10-01T12:00:02Z"), event.occurredAt());
        assertEquals("TN-01", event.status().nodes().get(0).id());
        assertEquals(24, event.status().nodes().get(0).locationId());
        assertEquals("OPEN", event.status().nodes().get(0).state());
        assertEquals(json, event.rawJson());
    }

    @Test
    void parsesStatusChangedWithoutProblemsOrLocation() throws Exception {
        String json = "{"
                + "\"eventType\":\"STATUS_CHANGED\","
                + "\"occurredAt\":\"2026-10-01T12:00:03Z\","
                + "\"payload\":{\"nodes\":[{\"id\":\"A\",\"locId\":null,\"state\":\"CLOSED\"}]}"
                + "}";

        ApiEventClient.ApiEvent parsed = ApiEventClient.parseEvent(json);
        ApiEventClient.StatusEvent event =
                assertInstanceOf(ApiEventClient.StatusEvent.class, parsed);

        assertEquals("A", event.status().nodes().get(0).id());
        assertEquals(null, event.status().nodes().get(0).locationId());
        assertEquals(List.of(), event.status().problems());
    }

    @Test
    void parsesCommittedTimingDataEvent() throws Exception {
        String json = "{"
                + "\"eventType\":\"TIMING_DATA_COMMITTED\","
                + "\"occurredAt\":\"2026-10-01T12:00:02Z\","
                + "\"payload\":{"
                + "\"v\":1,"
                + "\"nodeId\":\"TN-01\","
                + "\"seqNr\":3,"
                + "\"locId\":24,"
                + "\"recType\":\"AUTO_REG\","
                + "\"time\":\"2026-10-01T12:00:00Z\","
                + "\"regId\":\"N0003\","
                + "\"code\":[\"ADD\"],"
                + "\"recTime\":\"2026-10-01T12:00:00.125Z\""
                + "}"
                + "}";

        ApiEventClient.ApiEvent parsed = ApiEventClient.parseEvent(json);
        var event = assertInstanceOf(ApiEventClient.TimingDataEvent.class, parsed);

        assertEquals("TIMING_DATA_COMMITTED", event.eventType());
        assertEquals("TN-01", event.timingData().timingNodeId());
        assertEquals(3L, event.timingData().sequenceNumber());
        assertEquals("N0003", event.timingData().registrationId());
        assertEquals("AUTO_REG", event.timingData().recordType());
        assertEquals(List.of("ADD"), event.timingData().codes());
        assertEquals(
                new ApiClient.TimingDataKey("TN-01", 3L),
                event.timingData().key());
    }

    @Test
    void parsesTypedUpstreamDataEvent() throws Exception {
        String json = "{"
                + "\"eventType\":\"UPSTREAM_DATA\","
                + "\"occurredAt\":\"2026-10-01T12:00:02Z\","
                + "\"payload\":{"
                + "\"v\":1,\"type\":\"UP_PACKET\",\"nodeId\":\"A\","
                + "\"packetId\":0,"
                + "\"message\":{\"type\":\"PING\",\"data\":{"
                + "\"op\":\"REQ\",\"mode\":\"HB\","
                + "\"time\":\"2026-10-01T12:00:01.123456789Z\","
                + "\"pingId\":\"hb-1\"}}"
                + "}}";

        Instant receivedAt =
                Instant.parse("2026-10-01T12:00:02.250Z");
        ApiEventClient.ApiEvent parsed =
                ApiEventClient.parseEvent(json, receivedAt);
        ApiEventClient.UpstreamDataEvent event =
                assertInstanceOf(
                        ApiEventClient.UpstreamDataEvent.class,
                        parsed);
        UpstreamData packet = event.upstreamData();
        UpstreamData.Ping ping =
                assertInstanceOf(
                        UpstreamData.Ping.class,
                        packet.message());

        assertEquals(
                UpstreamData.Direction.UP_PACKET,
                packet.direction());
        assertEquals(0L, packet.packetId());
        assertEquals(
                UpstreamData.PingOperation.REQ,
                ping.operation());
        assertEquals(
                UpstreamData.PingMode.HB,
                ping.mode());
        assertEquals("hb-1", ping.pingId());
        assertEquals(
                Instant.parse(
                        "2026-10-01T12:00:01.123456789Z"),
                ping.time().instant());
        assertEquals(receivedAt, event.receivedAt());
        assertEquals(
                "{\"v\":1,\"type\":\"UP_PACKET\",\"nodeId\":\"A\","
                        + "\"packetId\":0,\"message\":{\"type\":\"PING\","
                        + "\"data\":{\"op\":\"REQ\",\"mode\":\"HB\","
                        + "\"time\":\"2026-10-01T12:00:01.123456789Z\","
                        + "\"pingId\":\"hb-1\"}}}",
                event.rawPayloadJson());
        assertFalse(
                event.rawPayloadJson()
                        .contains("\"eventType\""));
        assertEquals(json, event.rawJson());
        assertEquals(
                "A",
                packet.timingNodeId().value());
    }

    @Test
    void preservesUnknownEventForDiagnostics() throws Exception {
        String json = "{"
                + "\"eventType\":\"FUTURE_EVENT\","
                + "\"occurredAt\":\"2026-10-01T12:00:02Z\","
                + "\"payload\":{\"value\":1}"
                + "}";

        ApiEventClient.ApiEvent parsed = ApiEventClient.parseEvent(json);
        var event = assertInstanceOf(ApiEventClient.UnknownEvent.class, parsed);

        assertEquals("FUTURE_EVENT", event.eventType());
        assertEquals("{\"value\":1}", event.payloadJson());
        assertEquals(json, event.rawJson());
    }

    @Test
    void rejectsNonWebSocketEndpoint() {
        ApiEventClient client = new ApiEventClient();
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> client.connect(
                            java.net.URI.create("http://127.0.0.1:8082/api/v1/events"),
                            new NoOpListener()));
        } finally {
            client.close();
        }
    }

    private static final class NoOpListener implements ApiEventClient.Listener {
        @Override
        public void onConnected() {
        }

        @Override
        public void onEvent(ApiEventClient.ApiEvent event) {
        }

        @Override
        public void onClosed(int statusCode, String reason) {
        }

        @Override
        public void onError(String message) {
        }
    }
}
