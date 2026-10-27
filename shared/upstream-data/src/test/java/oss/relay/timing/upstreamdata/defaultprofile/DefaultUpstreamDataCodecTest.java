package oss.relay.timing.upstreamdata.defaultprofile;

import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingTimestamp;
import oss.relay.timing.timingdata.defaultprofile.DefaultTimingDataCodec;
import oss.relay.timing.upstreamdata.UpstreamData;
import oss.relay.timing.upstreamdata.UpstreamDataCodec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DefaultUpstreamDataCodecTest {
    private final DefaultTimingDataCodec timingCodec =
            new DefaultTimingDataCodec();
    private final DefaultUpstreamDataCodec codec =
            new DefaultUpstreamDataCodec(timingCodec);

    @Test
    public void composesUpPacketTimingDataWithoutChangingIdentity()
            throws Exception {
        NodeId nodeId = new NodeId("A");
        TimingRecord record =
                TimingRecord.createAutomaticRegistration(
                        'A',
                        42L,
                        1,
                        TimingTimestamp.parse("2026-10-13T08:00:00.12Z")
                                .instant()
                                .toEpochMilli(),
                        TimingTimestamp.parse("2026-10-13T08:00:00.123Z")
                                .instant()
                                .toEpochMilli(),
                        new RegistrationId("A0042").packedValue(),
                        TimingRecord.Action.ADD,
                        null,
                        null);

        UpstreamData packet =
                DefaultUpstreamData.createUpPacket(
                        nodeId,
                        57L,
                        DefaultUpstreamData.createTimingData(
                                Collections.singletonList(record)));

        String json = text(codec.encode(packet));
        assertTrue(
                json.startsWith(
                        "{\"v\":1,\"type\":\"UP_PACKET\",\"nodeId\":\"A\",\"pckNr\":57,"
                                + "\"msg\":{\"type\":\"TIMING_DATA\",\"data\":[{"));
        assertTrue(json.contains("\"seqNr\":42"));
        assertTrue(json.contains("\"time\":\"2026-10-13T08:00:00.12Z\""));
        assertTrue(json.contains("\"regId\":\"A0042\""));
        assertTrue(json.contains("\"recTime\":\"2026-10-13T08:00:00.123Z\""));

        UpstreamData decoded = codec.decode(bytes(json));
        assertSame(
                UpstreamData.Direction.UP_PACKET,
                decoded.direction());
        assertEquals(57L, decoded.packetNr());
        assertEquals(nodeId, decoded.timingNodeId());

        UpstreamData.TimingDataTransfer transfer =
                (UpstreamData.TimingDataTransfer) decoded.message();
        assertEquals(1, transfer.records().size());
        assertEquals(
                42L,
                transfer.records().get(0).sequenceNumber());
    }

    @Test
    public void roundTripsIndependentCurrentStateMessagesWithoutLocation()
            throws Exception {
        UpstreamData assignments =
                DefaultUpstreamData.createDnPacket(
                        new NodeId("A"),
                        43L,
                        DefaultUpstreamData.createTeamAssignments(
                                Arrays.asList(
                                        DefaultUpstreamData.createTeamAssignmentSet(
                                                "R0001",
                                                "0042"),
                                        DefaultUpstreamData.createTeamAssignmentDelete(
                                                "R0002"))));

        String assignmentJson = text(codec.encode(assignments));
        assertTrue(assignmentJson.contains("\"type\":\"TEAM_ASSIGNMENTS\""));
        assertTrue(!assignmentJson.contains("\"locationId\""));
        assertTrue(!assignmentJson.contains("\"seq\":"));

        UpstreamData decodedAssignments = codec.decode(bytes(assignmentJson));
        assertEquals(
                ((UpstreamData.TeamAssignments) assignments.message()).changes(),
                ((UpstreamData.TeamAssignments) decodedAssignments.message()).changes());

        UpstreamData stageStarts =
                DefaultUpstreamData.createDnPacket(
                        new NodeId("A"),
                        44L,
                        DefaultUpstreamData.createStageStarts(
                                Arrays.asList(
                                        DefaultUpstreamData.createStageStartSet(
                                                "R0001",
                                                TimingTimestamp.parse(
                                                        "23:59:50.12")),
                                        DefaultUpstreamData.createStageStartSet(
                                                "R0002",
                                                TimingTimestamp.parse(
                                                        "2026-10-14T00:02:00")))));

        String stageJson = text(codec.encode(stageStarts));
        assertTrue(stageJson.contains("\"type\":\"STAGE_STARTS\""));
        assertTrue(!stageJson.contains("\"locationId\""));
        assertTrue(!stageJson.contains("\"seq\":"));

        UpstreamData decodedStageStarts = codec.decode(bytes(stageJson));
        assertEquals(
                ((UpstreamData.StageStarts) stageStarts.message()).changes(),
                ((UpstreamData.StageStarts) decodedStageStarts.message()).changes());
    }

    @Test
    public void roundTripsSequenceRequestAnswerAndNotification()
            throws Exception {
        UpstreamData request =
                DefaultUpstreamData.createDnPacket(
                        new NodeId("A"),
                        10L,
                        DefaultUpstreamData.createSequenceRequest());
        UpstreamData answer =
                DefaultUpstreamData.createUpPacket(
                        new NodeId("A"),
                        11L,
                        DefaultUpstreamData.createSequenceAnswer(42L));
        UpstreamData notification =
                DefaultUpstreamData.createUpPacket(
                        new NodeId("A"),
                        12L,
                        DefaultUpstreamData.createSequenceNotification(43L));

        assertSequence(
                codec.decode(codec.encode(request)),
                UpstreamData.SequenceOperation.REQ,
                null);
        assertSequence(
                codec.decode(codec.encode(answer)),
                UpstreamData.SequenceOperation.ANS,
                Long.valueOf(42L));
        assertSequence(
                codec.decode(codec.encode(notification)),
                UpstreamData.SequenceOperation.NTF,
                Long.valueOf(43L));
    }

    @Test
    public void roundTripsTimingDataRequestAndReceive()
            throws Exception {
        UpstreamData request =
                DefaultUpstreamData.createDnPacket(
                        new NodeId("A"),
                        41L,
                        DefaultUpstreamData.createTimingDataRequest(1171L));
        UpstreamData decodedRequest =
                codec.decode(codec.encode(request));
        assertEquals(
                1171L,
                ((UpstreamData.TimingDataRequest)
                        decodedRequest.message())
                        .fromSequence());

        UpstreamData receive =
                DefaultUpstreamData.createUpPacket(
                        new NodeId("A"),
                        59L,
                        DefaultUpstreamData.createReceive(41L));
        UpstreamData decodedReceive =
                codec.decode(codec.encode(receive));
        assertEquals(
                41L,
                ((UpstreamData.Receive) decodedReceive.message())
                        .receivedPacketNr());

        UpstreamData fullResend =
                DefaultUpstreamData.createUpPacket(
                        new NodeId("A"),
                        60L,
                        DefaultUpstreamData.createReceive(0L));
        assertEquals(
                0L,
                ((UpstreamData.Receive)
                        codec.decode(codec.encode(fullResend))
                                .message())
                        .receivedPacketNr());
    }

    @Test
    public void pingUsesPacketZeroAndKeepsOwnMessageTime()
            throws Exception {
        TimingTimestamp messageTime =
                TimingTimestamp.parseAbsolute(
                        "2026-10-24T14:12:00.123Z");
        UpstreamData heartbeat =
                DefaultUpstreamData.createUpPacket(
                        new NodeId("A"),
                        0L,
                        DefaultUpstreamData.createPing(
                                UpstreamData.PingOperation.REQ,
                                UpstreamData.PingMode.HB,
                                "p-0001",
                                messageTime));

        assertEquals(
                "{\"v\":1,\"type\":\"UP_PACKET\",\"nodeId\":\"A\",\"pckNr\":0,"
                        + "\"msg\":{\"type\":\"PING\",\"data\":{\"op\":\"REQ\","
                        + "\"mode\":\"HB\",\"time\":\"2026-10-24T14:12:00.123Z\","
                        + "\"pingId\":\"p-0001\"}}}",
                text(codec.encode(heartbeat)));

        UpstreamData decoded = codec.decode(codec.encode(heartbeat));
        UpstreamData.Ping ping =
                (UpstreamData.Ping) decoded.message();
        assertEquals(0L, decoded.packetNr());
        assertSame(
                UpstreamData.PingOperation.REQ,
                ping.operation());
        assertSame(
                UpstreamData.PingMode.HB,
                ping.mode());
        assertEquals("p-0001", ping.pingId());
        assertEquals(messageTime, ping.time());

        UpstreamData manual =
                codec.decode(
                        bytes(
                                "{\"v\":1,\"type\":\"DN_PACKET\",\"nodeId\":\"A\","
                                        + "\"pckNr\":0,\"msg\":{\"type\":\"PING\","
                                        + "\"data\":{\"op\":\"ANS\"}}}"));
        UpstreamData.Ping manualPing =
                (UpstreamData.Ping) manual.message();
        assertSame(
                UpstreamData.PingOperation.ANS,
                manualPing.operation());
        assertNull(manualPing.mode());
        assertNull(manualPing.pingId());
        assertNull(manualPing.time());
    }

    @Test
    public void pingWriterTruncatesToMillisButReaderAcceptsNanoseconds()
            throws Exception {
        String fullPrecision = "2026-10-24T20:12:04.8234159Z";
        UpstreamData original =
                DefaultUpstreamData.createUpPacket(
                        new NodeId("A"),
                        0L,
                        DefaultUpstreamData.createPing(
                                UpstreamData.PingOperation.REQ,
                                UpstreamData.PingMode.HB,
                                "hb-2",
                                TimingTimestamp.parseAbsolute(fullPrecision)));

        String encoded = text(codec.encode(original));
        assertTrue(encoded.contains("2026-10-24T20:12:04.823Z"));

        String legacyEncoded = encoded.replace(
                "2026-10-24T20:12:04.823Z",
                fullPrecision);
        UpstreamData decoded = codec.decode(bytes(legacyEncoded));
        assertEquals(
                TimingTimestamp.parseAbsolute(fullPrecision),
                ((UpstreamData.Ping) decoded.message()).time());
        assertEquals(
                encoded,
                text(codec.encode(decoded)));
    }

    @Test
    public void rejectsPacketAndMessageContractViolations()
            throws Exception {
        assertInvalid(
                "{\"v\":1,\"type\":\"UP_PACKET\",\"nodeId\":\"A\",\"pckNr\":0,"
                        + "\"msg\":{\"type\":\"RECV\","
                        + "\"data\":{\"rcvSeqNr\":0}}}");

        assertInvalid(
                "{\"v\":1,\"type\":\"DN_PACKET\",\"nodeId\":\"A\",\"pckNr\":1,"
                        + "\"msg\":{\"type\":\"PING\","
                        + "\"data\":{\"op\":\"REQ\"}}}");

        assertInvalid(
                "{\"v\":1,\"type\":\"UP_PACKET\",\"nodeId\":\"A\",\"pckNr\":1,"
                        + "\"msg\":{\"type\":\"TEAM_ASSIGNMENTS\","
                        + "\"data\":[{\"op\":\"SET\",\"regId\":\"R0001\","
                        + "\"teamId\":\"0042\"}]}}");

        assertInvalid(
                "{\"v\":1,\"type\":\"DN_PACKET\",\"nodeId\":\"A\",\"pckNr\":1,"
                        + "\"msg\":{\"type\":\"SEQ\","
                        + "\"data\":{\"op\":\"REQ\",\"seq\":1}}}");

        assertInvalid(
                "{\"v\":1,\"type\":\"DN_PACKET\",\"nodeId\":\"A\",\"pckNr\":1,"
                        + "\"msg\":{\"type\":\"TEAM_ASSIGNMENTS\","
                        + "\"data\":[{\"op\":\"SET\",\"regId\":\"R0001\"}]}}");
    }

    @Test
    public void stageStartTimestampMayBeTimeOnly()
            throws Exception {
        UpstreamData packet =
                DefaultUpstreamData.createDnPacket(
                        new NodeId("A"),
                        1L,
                        DefaultUpstreamData.createStageStarts(
                                Collections.singletonList(
                                        DefaultUpstreamData.createStageStartSet(
                                                "R0001",
                                                TimingTimestamp.parse(
                                                        "12:00:00.123")))));

        UpstreamData decoded =
                codec.decode(codec.encode(packet));
        UpstreamData.StageStarts stageStarts =
                (UpstreamData.StageStarts) decoded.message();
        assertEquals(
                TimingTimestamp.Knowledge.TIME_ONLY,
                stageStarts.changes()
                        .get(0)
                        .start()
                        .knowledge());
    }

    @Test
    public void reportsUnsupportedVersionAndMessageTypeSeparately()
            throws Exception {
        try {
            codec.decode(
                    bytes(
                            "{\"v\":2,\"type\":\"UP_PACKET\",\"nodeId\":\"A\","
                                    + "\"pckNr\":1,\"msg\":{\"type\":\"RECV\","
                                    + "\"data\":{\"rcvSeqNr\":0}}}"));
            fail("expected unsupported version");
        } catch (UpstreamDataCodec.CodecException expected) {
            assertSame(
                    UpstreamDataCodec.CodecException.Reason.UNSUPPORTED_VERSION,
                    expected.reason());
        }

        try {
            codec.decode(
                    bytes(
                            "{\"v\":1,\"type\":\"UP_PACKET\",\"nodeId\":\"A\","
                                    + "\"pckNr\":1,\"msg\":{\"type\":\"FUTURE\","
                                    + "\"data\":{}}}"));
            fail("expected unsupported message type");
        } catch (UpstreamDataCodec.CodecException expected) {
            assertSame(
                    UpstreamDataCodec.CodecException.Reason.UNSUPPORTED_MESSAGE_TYPE,
                    expected.reason());
        }
    }

    @Test
    public void providerUsesStableReferenceId() {
        DefaultUpstreamDataProvider provider =
                new DefaultUpstreamDataProvider();
        assertEquals("reference", provider.id());
        assertTrue(
                provider.createCodec(timingCodec)
                        instanceof DefaultUpstreamDataCodec);
    }

    private static void assertSequence(
            UpstreamData packet,
            UpstreamData.SequenceOperation operation,
            Long position) {
        UpstreamData.Sequence sequence =
                (UpstreamData.Sequence) packet.message();
        assertSame(operation, sequence.operation());
        assertEquals(position, sequence.sequence());
    }

    private void assertInvalid(String json)
            throws Exception {
        try {
            codec.decode(bytes(json));
            fail("expected invalid data");
        } catch (UpstreamDataCodec.CodecException expected) {
            assertSame(
                    UpstreamDataCodec.CodecException.Reason.INVALID_DATA,
                    expected.reason());
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
