package io.github.brainboxemb.eventtiming.testclient;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CentralUpstreamClientTest {
    @Test
    void extractsUpstreamPayloadWithoutInterpretingSemanticData() throws Exception {
        String raw = "{"
                + "\"eventType\":\"UPSTREAM_DATA\","
                + "\"occurredAt\":\"2026-10-24T08:00:00Z\","
                + "\"payload\":{"
                + "\"v\":1,\"type\":\"PING\",\"nodeId\":\"A\","
                + "\"data\":{\"op\":\"REQ\",\"mode\":\"HB\",\"pingId\":\"hb-1\"}"
                + "}}";

        CentralUpstreamClient.Envelope envelope =
                CentralUpstreamClient.parseEnvelope(raw);

        assertEquals("UPSTREAM_DATA", envelope.eventType());
        assertEquals(
                Instant.parse("2026-10-24T08:00:00Z"),
                envelope.occurredAt());
        assertEquals(
                "{\"v\":1,\"type\":\"PING\",\"nodeId\":\"A\","
                        + "\"data\":{\"op\":\"REQ\",\"mode\":\"HB\","
                        + "\"pingId\":\"hb-1\"}}",
                envelope.payloadJson());
    }
}
