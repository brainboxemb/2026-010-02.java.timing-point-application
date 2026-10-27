package oss.relay.timing.testclient.central;

import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.upstreamdata.UpstreamData;
import oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamData;

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
        UpstreamData packet =
                DefaultUpstreamData.createDnPacket(
                        NODE_A,
                        7L,
                        DefaultUpstreamData.createSequenceNotification(4L));
        CentralSystemSimulator.MessageView message =
                new CentralSystemSimulator.MessageView(
                        1L,
                        CentralSystemSimulator.MessageDirection.OUT,
                        TIME,
                        null,
                        packet);

        assertTrue(CentralMessagesPane.matches(message, "ALL", "ALL", "ALL"));
        assertTrue(CentralMessagesPane.matches(message, "OUT", "A", "SEQ"));
        assertFalse(CentralMessagesPane.matches(message, "IN", "A", "SEQ"));
        assertFalse(CentralMessagesPane.matches(message, "OUT", "B", "SEQ"));
        assertFalse(CentralMessagesPane.matches(message, "OUT", "A", "RECV"));
    }

    @Test
    void visibleRowsAlwaysEndWithFollowMarker() {
        UpstreamData ping = pingPacket(UpstreamData.Direction.UP_PACKET);
        CentralSystemSimulator.MessageView inbound =
                new CentralSystemSimulator.MessageView(
                        1L,
                        CentralSystemSimulator.MessageDirection.IN,
                        TIME,
                        TIME.plusMillis(4),
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
        UpstreamData ping = pingPacket(UpstreamData.Direction.UP_PACKET);
        CentralSystemSimulator.MessageView inbound =
                new CentralSystemSimulator.MessageView(
                        1L,
                        CentralSystemSimulator.MessageDirection.IN,
                        TIME,
                        TIME.plusMillis(4),
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
    void liveRowDeltaAppendsBeforeFollowEndWithoutReplacingHistory() {
        CentralSystemSimulator.MessageView first =
                messageView(1L);
        CentralSystemSimulator.MessageView second =
                messageView(2L);

        java.util.List<CentralMessagesPane.MessageRow> current =
                CentralMessagesPane.visibleRows(
                        java.util.List.of(first),
                        "ALL",
                        "ALL",
                        "ALL");
        java.util.List<CentralMessagesPane.MessageRow> next =
                CentralMessagesPane.visibleRows(
                        java.util.List.of(first, second),
                        "ALL",
                        "ALL",
                        "ALL");

        assertEquals(
                new CentralMessagesPane.RowDelta(0, 1),
                CentralMessagesPane.liveRowDelta(current, next));
    }

    @Test
    void liveRowDeltaHandlesBoundedJournalRolloverAsHeadRemoveAndTailAppend() {
        CentralSystemSimulator.MessageView first =
                messageView(1L);
        CentralSystemSimulator.MessageView second =
                messageView(2L);
        CentralSystemSimulator.MessageView third =
                messageView(3L);

        java.util.List<CentralMessagesPane.MessageRow> current =
                CentralMessagesPane.visibleRows(
                        java.util.List.of(first, second),
                        "ALL",
                        "ALL",
                        "ALL");
        java.util.List<CentralMessagesPane.MessageRow> next =
                CentralMessagesPane.visibleRows(
                        java.util.List.of(second, third),
                        "ALL",
                        "ALL",
                        "ALL");

        assertEquals(
                new CentralMessagesPane.RowDelta(1, 1),
                CentralMessagesPane.liveRowDelta(current, next));
    }

    @Test
    void formatsTimeCompactlyAtMillisecondPrecision() {
        Instant precise =
                Instant.parse("2026-10-24T14:29:46.798293700Z");

        java.time.ZoneId amsterdam =
                java.time.ZoneId.of("Europe/Amsterdam");
        assertEquals(
                "16:29:46.798",
                CentralMessagesPane.formatTime(
                        precise,
                        false,
                        amsterdam));
        assertEquals(
                "2026-10-24 16:29:46.798",
                CentralMessagesPane.formatTime(
                        precise,
                        true,
                        amsterdam));
        assertEquals(
                "16:29:46",
                CentralMessagesPane.formatTime(
                        Instant.parse("2026-10-24T14:29:46Z"),
                        false,
                        amsterdam));
        assertEquals(
                "—",
                CentralMessagesPane.formatTime(
                        null,
                        false,
                        java.time.ZoneId.of("Europe/Amsterdam")));
    }

    @Test
    void packetPositionExplainsSeqRequestAndReceiveSemantics() {
        UpstreamData sequence =
                DefaultUpstreamData.createDnPacket(
                        NODE_A,
                        7L,
                        DefaultUpstreamData.createSequenceAnswer(42L));
        UpstreamData request =
                DefaultUpstreamData.createDnPacket(
                        NODE_A,
                        8L,
                        DefaultUpstreamData.createTimingDataRequest(43L));
        UpstreamData receive =
                DefaultUpstreamData.createUpPacket(
                        NODE_A,
                        9L,
                        DefaultUpstreamData.createReceive(8L));

        assertEquals(
                "P7 · ANS 42",
                CentralMessagesPane.positionText(sequence));
        assertEquals(
                "TimingData position ANS",
                CentralMessagesPane.summaryText(sequence));
        assertEquals(
                "P8 · from 43",
                CentralMessagesPane.positionText(request));
        assertEquals(
                "request retained TimingData",
                CentralMessagesPane.summaryText(request));
        assertEquals(
                "P9 · DN through 8",
                CentralMessagesPane.positionText(receive));
        assertEquals(
                "report last processed DN_PACKET",
                CentralMessagesPane.summaryText(receive));
    }

    @Test
    void timingDataPositionUsesRecordTypeInsteadOfRepeatingMessageType() {
        long millis = TIME.toEpochMilli();
        TimingRecord automatic =
                TimingRecord.createAutomaticRegistration(
                        'A',
                        1186L,
                        1,
                        millis,
                        millis,
                        new RegistrationId("A0001").packedValue(),
                        TimingRecord.Action.ADD,
                        TimingRecord.TagSource.API,
                        TimingRecord.AutomaticTimeSource.API);

        UpstreamData packet =
                timingPacket(
                        15L,
                        java.util.List.of(automatic));

        assertEquals(
                "P15 · AUTO_REG #1186",
                CentralMessagesPane.positionText(packet));
    }

    @Test
    void timingDataPositionShowsOnlySequenceRangeForAnyBatch() {
        long millis = TIME.toEpochMilli();
        TimingRecord first =
                TimingRecord.createNodeOpened(
                        'A',
                        1186L,
                        1,
                        millis,
                        millis);
        TimingRecord second =
                TimingRecord.createNodeOpened(
                        'A',
                        1187L,
                        1,
                        millis,
                        millis);
        TimingRecord mixed =
                TimingRecord.createNodeClosed(
                        'A',
                        1188L,
                        1,
                        millis,
                        millis);

        assertEquals(
                "P16 · #1186..1187",
                CentralMessagesPane.positionText(
                        timingPacket(
                                16L,
                                java.util.List.of(first, second))));
        assertEquals(
                "P17 · #1186..1188",
                CentralMessagesPane.positionText(
                        timingPacket(
                                17L,
                                java.util.List.of(first, second, mixed))));
    }

    @Test
    void timingDataBatchDetailShowsOneCompactRecordPerLine() {
        long millis = TIME.toEpochMilli();
        TimingRecord first =
                TimingRecord.createNodeOpened(
                        'A',
                        1186L,
                        1,
                        millis,
                        millis);
        TimingRecord second =
                TimingRecord.createNodeClosed(
                        'A',
                        1187L,
                        1,
                        millis,
                        millis);
        UpstreamData packet =
                timingPacket(
                        18L,
                        java.util.List.of(first, second));
        String raw =
                CentralUpstreamJson.encode(packet);

        String displayed =
                CentralMessagesPane.detailPayload(
                        packet,
                        raw);
        String[] lines = displayed.split("\\R");

        assertEquals(4, lines.length);
        assertTrue(lines[0].endsWith("\"data\":["));
        assertTrue(lines[1].trim().startsWith("{"));
        assertTrue(lines[1].trim().endsWith("},"));
        assertTrue(lines[2].trim().startsWith("{"));
        assertTrue(lines[2].trim().endsWith("}"));
        assertEquals("]}}", lines[3]);
        assertEquals(
                raw,
                displayed.replace("\n  ", "").replace("\n", ""));
    }

    @Test
    void singleTimingDataDetailKeepsRawPayloadUnchanged() {
        long millis = TIME.toEpochMilli();
        TimingRecord record =
                TimingRecord.createNodeOpened(
                        'A',
                        1186L,
                        1,
                        millis,
                        millis);
        UpstreamData packet =
                timingPacket(
                        19L,
                        java.util.List.of(record));
        String raw =
                CentralUpstreamJson.encode(packet);

        assertEquals(
                raw,
                CentralMessagesPane.detailPayload(
                        packet,
                        raw));
    }

    @Test
    void currentStateDetailFormatsEachChangeOnItsOwnLine() {
        UpstreamData assignments =
                DefaultUpstreamData.createDnPacket(
                        NODE_A,
                        20L,
                        DefaultUpstreamData.createTeamAssignments(
                                java.util.List.of(
                                        DefaultUpstreamData.createTeamAssignmentSet(
                                                "A0001",
                                                "T001"),
                                        DefaultUpstreamData.createTeamAssignmentSet(
                                                "A0002",
                                                "T002"))));
        String assignmentRaw = CentralUpstreamJson.encode(assignments);
        String assignmentDisplayed =
                CentralMessagesPane.detailPayload(
                        assignments,
                        assignmentRaw);

        assertTrue(
                assignmentDisplayed.contains(
                        "\"data\":[\n  {"));
        assertTrue(
                assignmentDisplayed.contains(
                        "},\n  {\"op\":\"SET\",\"regId\":\"A0002\""));
        assertEquals(
                assignmentRaw,
                assignmentDisplayed.replace("\n  ", "").replace("\n", ""));

        UpstreamData stageStarts =
                DefaultUpstreamData.createDnPacket(
                        NODE_A,
                        21L,
                        DefaultUpstreamData.createStageStarts(
                                java.util.List.of(
                                        DefaultUpstreamData.createStageStartSet(
                                                "A0001",
                                                oss.relay.timing.timingdata.TimingTimestamp.parse(
                                                        "10:00:00")),
                                        DefaultUpstreamData.createStageStartSet(
                                                "A0002",
                                                oss.relay.timing.timingdata.TimingTimestamp.parse(
                                                        "10:00:30")))));
        String stageRaw = CentralUpstreamJson.encode(stageStarts);
        String stageDisplayed =
                CentralMessagesPane.detailPayload(
                        stageStarts,
                        stageRaw);

        assertTrue(
                stageDisplayed.contains(
                        "\"data\":[\n  {"));
        assertEquals(
                stageRaw,
                stageDisplayed.replace("\n  ", "").replace("\n", ""));
    }

    @Test
    void currentStatePositionUsesOnlyPacketNr() {
        UpstreamData assignments =
                DefaultUpstreamData.createDnPacket(
                        NODE_A,
                        12L,
                        DefaultUpstreamData.createTeamAssignments(
                                java.util.List.of(
                                        DefaultUpstreamData.createTeamAssignmentSet(
                                                "A0001",
                                                "T001"),
                                        DefaultUpstreamData.createTeamAssignmentSet(
                                                "A0002",
                                                "T002"))));

        assertEquals(
                "P12",
                CentralMessagesPane.positionText(assignments));
        assertEquals(
                "2 team assignment change(s)",
                CentralMessagesPane.summaryText(assignments));

        UpstreamData stageStarts =
                DefaultUpstreamData.createDnPacket(
                        NODE_A,
                        13L,
                        DefaultUpstreamData.createStageStarts(
                                java.util.List.of(
                                        DefaultUpstreamData.createStageStartDelete(
                                                "A0001"))));

        assertEquals(
                "P13",
                CentralMessagesPane.positionText(stageStarts));
        assertEquals(
                "1 stage-start change(s)",
                CentralMessagesPane.summaryText(stageStarts));
    }

    @Test
    void directionTextMakesCentralFlowVisible() {
        UpstreamData ping = pingPacket(UpstreamData.Direction.UP_PACKET);

        CentralSystemSimulator.MessageView inbound =
                new CentralSystemSimulator.MessageView(
                        1L,
                        CentralSystemSimulator.MessageDirection.IN,
                        TIME,
                        TIME.plusMillis(4),
                        ping);
        CentralSystemSimulator.MessageView outbound =
                new CentralSystemSimulator.MessageView(
                        2L,
                        CentralSystemSimulator.MessageDirection.OUT,
                        TIME,
                        null,
                        DefaultUpstreamData.createDnPacket(
                                NODE_A,
                                0L,
                                DefaultUpstreamData.createPing(
                                        UpstreamData.PingOperation.ANS,
                                        UpstreamData.PingMode.HB,
                                        "hb-1")));

        assertEquals("IN ←", CentralMessagesPane.directionText(inbound));
        assertEquals("OUT →", CentralMessagesPane.directionText(outbound));
    }

    private static CentralSystemSimulator.MessageView messageView(
            long ordinal) {
        return new CentralSystemSimulator.MessageView(
                ordinal,
                CentralSystemSimulator.MessageDirection.IN,
                TIME.plusMillis(ordinal),
                TIME.plusMillis(ordinal + 1L),
                pingPacket(UpstreamData.Direction.UP_PACKET));
    }

    private static UpstreamData timingPacket(
            long packetNr,
            java.util.List<TimingRecord> records) {
        return DefaultUpstreamData.createUpPacket(
                NODE_A,
                packetNr,
                DefaultUpstreamData.createTimingData(records));
    }

    private static UpstreamData pingPacket(
            UpstreamData.Direction direction) {
        UpstreamData.Ping ping =
                DefaultUpstreamData.createPing(
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        "hb-1");
        return direction == UpstreamData.Direction.UP_PACKET
                ? DefaultUpstreamData.createUpPacket(
                        NODE_A,
                        0L,
                        ping)
                : DefaultUpstreamData.createDnPacket(
                        NODE_A,
                        0L,
                        ping);
    }
}
