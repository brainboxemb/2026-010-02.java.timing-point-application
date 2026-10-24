package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CentralMessagesPaneTest {
    private static final NodeId NODE_A = new NodeId("A");
    private static final Instant TIME =
            Instant.parse("2026-10-24T11:00:00Z");

    @Test
    void filtersDirectionNodeAndTypeIndependently() {
        UpstreamData.Sync sync =
                DefaultUpstreamData.sync(
                        NODE_A,
                        UpstreamData.Stream.RACE_DATA,
                        7L);
        CentralSystemSimulator.MessageView message =
                new CentralSystemSimulator.MessageView(
                        1L,
                        CentralSystemSimulator.MessageDirection.OUT,
                        TIME,
                        sync);

        assertTrue(CentralMessagesPane.matches(message, "ALL", "ALL", "ALL"));
        assertTrue(CentralMessagesPane.matches(message, "OUT", "A", "SYNC"));
        assertFalse(CentralMessagesPane.matches(message, "IN", "A", "SYNC"));
        assertFalse(CentralMessagesPane.matches(message, "OUT", "B", "SYNC"));
        assertFalse(CentralMessagesPane.matches(message, "OUT", "A", "ACK"));
    }

    @Test
    void visibleRowsAlwaysEndWithFollowMarker() {
        UpstreamData.Ping ping =
                DefaultUpstreamData.ping(
                        NODE_A,
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        "hb-1");
        CentralSystemSimulator.MessageView inbound =
                new CentralSystemSimulator.MessageView(
                        1L,
                        CentralSystemSimulator.MessageDirection.IN,
                        TIME,
                        ping);

        java.util.List<CentralMessagesPane.MessageRow> rows =
                CentralMessagesPane.visibleRows(
                        java.util.List.of(inbound),
                        "IN",
                        "A",
                        "PING");

        assertEquals(2, rows.size());
        assertFalse(rows.get(0).followEnd());
        assertEquals(1L, rows.get(0).message().ordinal());
        assertTrue(rows.get(1).followEnd());
    }

    @Test
    void filteredOutMessagesStillLeaveFollowMarker() {
        UpstreamData.Ping ping =
                DefaultUpstreamData.ping(
                        NODE_A,
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        "hb-1");
        CentralSystemSimulator.MessageView inbound =
                new CentralSystemSimulator.MessageView(
                        1L,
                        CentralSystemSimulator.MessageDirection.IN,
                        TIME,
                        ping);

        java.util.List<CentralMessagesPane.MessageRow> rows =
                CentralMessagesPane.visibleRows(
                        java.util.List.of(inbound),
                        "OUT",
                        "A",
                        "PING");

        assertEquals(1, rows.size());
        assertTrue(rows.get(0).followEnd());
    }

    @Test
    void syncReqAndAckExplainTheirSequenceMeaning() {
        UpstreamData.Sync sync =
                DefaultUpstreamData.sync(
                        NODE_A,
                        UpstreamData.Stream.RACE_DATA,
                        7L);
        UpstreamData.Req req =
                DefaultUpstreamData.req(
                        NODE_A,
                        UpstreamData.Stream.RACE_DATA,
                        4L);
        UpstreamData.Ack ack =
                DefaultUpstreamData.ack(
                        NODE_A,
                        UpstreamData.Stream.RACE_DATA,
                        6L);

        assertEquals("RACE_DATA head 7", CentralMessagesPane.positionText(sync));
        assertEquals("advertise source head", CentralMessagesPane.summaryText(sync));
        assertEquals("RACE_DATA from 4", CentralMessagesPane.positionText(req));
        assertEquals("request replay from sequence", CentralMessagesPane.summaryText(req));
        assertEquals("RACE_DATA through 6", CentralMessagesPane.positionText(ack));
        assertEquals(
                "acknowledge contiguous receive head",
                CentralMessagesPane.summaryText(ack));
    }

    @Test
    void raceDataPositionIsMessageSequenceNotChangeCount() {
        UpstreamData.RaceData raceData =
                DefaultUpstreamData.raceData(
                        NODE_A,
                        12L,
                        java.util.List.of(
                                DefaultUpstreamData.teamAssignmentSet(
                                        "A0001",
                                        "T001"),
                                DefaultUpstreamData.teamAssignmentSet(
                                        "A0002",
                                        "T002")),
                        java.util.List.of());

        assertEquals("RACE_DATA #12", CentralMessagesPane.positionText(raceData));
        assertEquals(
                "2 team change(s), 0 stage-start change(s)",
                CentralMessagesPane.summaryText(raceData));
    }

    @Test
    void directionTextMakesCentralFlowVisible() {
        UpstreamData.Ping ping =
                DefaultUpstreamData.ping(
                        NODE_A,
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        "hb-1");

        CentralSystemSimulator.MessageView inbound =
                new CentralSystemSimulator.MessageView(
                        1L,
                        CentralSystemSimulator.MessageDirection.IN,
                        TIME,
                        ping);
        CentralSystemSimulator.MessageView outbound =
                new CentralSystemSimulator.MessageView(
                        2L,
                        CentralSystemSimulator.MessageDirection.OUT,
                        TIME,
                        ping);

        assertEquals("IN ←", CentralMessagesPane.directionText(inbound));
        assertEquals("OUT →", CentralMessagesPane.directionText(outbound));
    }
}
