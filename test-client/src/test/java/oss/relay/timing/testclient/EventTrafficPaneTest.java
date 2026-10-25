package oss.relay.timing.testclient;

import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.upstreamdata.UpstreamData;
import oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EventTrafficPaneTest {
    private static final Instant TIME =
            Instant.parse("2026-10-24T10:00:00Z");

    @Test
    void filtersDirectionAndTypeIndependently() {
        EventTrafficPane.Entry inbound =
                new EventTrafficPane.Entry(
                        EventTrafficPane.Direction.IN,
                        TIME,
                        "PING",
                        "{\"in\":1}");
        EventTrafficPane.Entry outbound =
                new EventTrafficPane.Entry(
                        EventTrafficPane.Direction.OUT,
                        TIME,
                        "PING",
                        "{\"out\":1}");

        assertTrue(EventTrafficPane.matches(inbound, "ALL", "ALL"));
        assertTrue(EventTrafficPane.matches(inbound, "IN", "PING"));
        assertFalse(EventTrafficPane.matches(inbound, "OUT", "PING"));
        assertTrue(EventTrafficPane.matches(outbound, "OUT", "PING"));
        assertFalse(EventTrafficPane.matches(outbound, "OUT", "RACE_DATA"));
    }

    @Test
    void upstreamEventIsExcludedFromNodeEvents() {
        UpstreamData ping =
                DefaultUpstreamData.createUpPacket(
                        new NodeId("A"),
                        0L,
                        DefaultUpstreamData.createPing(
                                UpstreamData.PingOperation.REQ,
                                UpstreamData.PingMode.HB,
                                "hb-1"));
        ApiEventClient.UpstreamDataEvent event =
                new ApiEventClient.UpstreamDataEvent(
                        "UPSTREAM_DATA",
                        TIME,
                        TIME.plusMillis(3),
                        ping,
                        "{\"eventType\":\"UPSTREAM_DATA\"}");

        assertFalse(EventTrafficPane.isNodeEvent(event));
        assertEquals("UPSTREAM_DATA", EventTrafficPane.typeOf(event));
    }

    @Test
    void normalEventKeepsItsEventType() {
        ApiEventClient.UnknownEvent event =
                new ApiEventClient.UnknownEvent(
                        "STATUS_SOMETHING",
                        TIME,
                        "{}",
                        "{\"eventType\":\"STATUS_SOMETHING\"}");

        assertTrue(EventTrafficPane.isNodeEvent(event));
        assertEquals("STATUS_SOMETHING", EventTrafficPane.typeOf(event));
    }

    @Test
    void outboundEncodingIsExactSharedUpstreamJsonWithoutEventEnvelope() {
        UpstreamData ping =
                DefaultUpstreamData.createDnPacket(
                        new NodeId("A"),
                        0L,
                        DefaultUpstreamData.createPing(
                                UpstreamData.PingOperation.ANS,
                                UpstreamData.PingMode.HB,
                                "hb-7"));

        String raw = ApiEventClient.encodeUpstreamData(ping);

        assertTrue(raw.contains("\"type\":\"PING\""));
        assertTrue(raw.contains("\"nodeId\":\"A\""));
        assertFalse(raw.contains("\"eventType\""));
        assertFalse(raw.contains(System.lineSeparator() + System.lineSeparator()));
    }
}
