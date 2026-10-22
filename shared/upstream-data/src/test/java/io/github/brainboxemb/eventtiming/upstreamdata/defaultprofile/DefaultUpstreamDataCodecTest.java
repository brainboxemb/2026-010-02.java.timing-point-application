package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;

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
    public void composesTimingDataJsonWithoutChangingIdentity() throws Exception {
        NodeId nodeId = new NodeId("A");
        TimingRecord record = TimingRecord.createAutomaticRegistration(
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

        UpstreamData.TimingDataTransfer transfer =
                DefaultUpstreamData.timingData(
                        nodeId,
                        Collections.singletonList(record));

        String json = text(codec.encode(transfer));
        assertTrue(json.startsWith(
                "{\"v\":1,\"type\":\"TIMING_DATA\",\"nodeId\":\"A\",\"data\":[{"));
        assertTrue(json.contains("\"seqNr\":42"));
        assertTrue(json.contains("\"regId\":\"A0042\""));

        UpstreamData.TimingDataTransfer decoded =
                (UpstreamData.TimingDataTransfer) codec.decode(bytes(json));
        assertEquals(nodeId, decoded.timingNodeId());
        assertEquals(1, decoded.records().size());
        assertEquals(42L, decoded.records().get(0).sequenceNumber());
        assertEquals(nodeId.value().charAt(0), decoded.records().get(0).nodeId());
    }

    @Test
    public void roundTripsOrderedIncrementalRaceData() throws Exception {
        UpstreamData.RaceData original =
                DefaultUpstreamData.raceData(
                        new NodeId("A"),
                        123L,
                        Arrays.asList(
                                DefaultUpstreamData.teamAssignmentSet(
                                        "R0001",
                                        "0042"),
                                DefaultUpstreamData.teamAssignmentDelete(
                                        "R0002")),
                        Arrays.asList(
                                DefaultUpstreamData.stageStartSet(
                                        "0042",
                                        TimingTimestamp.parse(
                                                "23:59:50.12")),
                                DefaultUpstreamData.stageStartSet(
                                        "0043",
                                        TimingTimestamp.parse(
                                                "2026-10-14T00:02:00"))));

        String json = text(codec.encode(original));
        assertTrue(json.contains("\"type\":\"RACE_DATA\""));
        assertTrue(json.contains("\"seq\":123"));
        assertTrue(json.contains("\"teamAssignments\""));
        assertTrue(json.contains("\"stageStartTimes\""));
        assertTrue(json.contains("\"op\":\"SET\""));
        assertTrue(json.contains("\"op\":\"DEL\""));
        assertTrue(json.contains(
                "\"start\":\"23:59:50.12\""));
        assertTrue(json.contains(
                "\"start\":\"2026-10-14T00:02:00\""));

        UpstreamData.RaceData decoded =
                (UpstreamData.RaceData) codec.decode(bytes(json));
        assertEquals(123L, decoded.sequence());
        assertEquals(
                original.teamAssignmentChanges(),
                decoded.teamAssignmentChanges());
        assertEquals(
                original.stageStartTimeChanges(),
                decoded.stageStartTimeChanges());
    }

    @Test
    public void raceDataRequiresAtLeastOneChange() throws Exception {
        assertInvalid(
                "{\"v\":1,\"type\":\"RACE_DATA\",\"nodeId\":\"A\","
                        + "\"seq\":1,\"data\":{}}");
    }

    @Test
    public void raceDataRejectsInvalidSetAndDeleteShapes() throws Exception {
        assertInvalid(
                "{\"v\":1,\"type\":\"RACE_DATA\",\"nodeId\":\"A\","
                        + "\"seq\":1,\"data\":{\"teamAssignments\":["
                        + "{\"op\":\"SET\",\"regId\":\"R0001\"}]}}");

        assertInvalid(
                "{\"v\":1,\"type\":\"RACE_DATA\",\"nodeId\":\"A\","
                        + "\"seq\":2,\"data\":{\"stageStartTimes\":["
                        + "{\"op\":\"DEL\",\"teamId\":\"0042\","
                        + "\"start\":\"12:00:00\"}]}}");
    }

    @Test
    public void stageStartTimestampMayBeTimeOnly() throws Exception {
        UpstreamData.RaceData data =
                DefaultUpstreamData.raceData(
                        new NodeId("A"),
                        1L,
                        Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                        Collections.singletonList(
                                DefaultUpstreamData.stageStartSet(
                                        "0042",
                                        TimingTimestamp.parse(
                                                "12:00:00.123"))));

        String json = text(codec.encode(data));
        assertTrue(json.contains(
                "\"start\":\"12:00:00.123\""));

        UpstreamData.RaceData decoded =
                (UpstreamData.RaceData) codec.decode(bytes(json));
        assertEquals(
                TimingTimestamp.Knowledge.TIME_ONLY,
                decoded.stageStartTimeChanges()
                        .get(0)
                        .start()
                        .knowledge());
    }

    @Test
    public void roundTripsHeartbeatAndManualPing() throws Exception {
        UpstreamData.Ping heartbeat =
                DefaultUpstreamData.ping(
                        new NodeId("A"),
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        "p-0001");

        assertEquals(
                "{\"v\":1,\"type\":\"PING\",\"nodeId\":\"A\",\"data\":{\"op\":\"REQ\",\"mode\":\"HB\",\"pingId\":\"p-0001\"}}",
                text(codec.encode(heartbeat)));

        UpstreamData.Ping decodedHeartbeat =
                (UpstreamData.Ping) codec.decode(codec.encode(heartbeat));
        assertEquals(
                new NodeId("A"),
                decodedHeartbeat.timingNodeId());
        assertSame(
                UpstreamData.PingOperation.REQ,
                decodedHeartbeat.operation());
        assertSame(
                UpstreamData.PingMode.HB,
                decodedHeartbeat.mode());
        assertEquals("p-0001", decodedHeartbeat.pingId());

        UpstreamData.Ping manual =
                (UpstreamData.Ping) codec.decode(
                        bytes("{\"v\":1,\"type\":\"PING\",\"nodeId\":\"A\",\"data\":{\"op\":\"ANS\"}}"));
        assertSame(UpstreamData.PingOperation.ANS, manual.operation());
        assertNull(manual.mode());
        assertNull(manual.pingId());
    }

    @Test
    public void rejectsUnknownPingModeRatherThanTreatingItAsManual()
            throws Exception {
        assertInvalid(
                "{\"v\":1,\"type\":\"PING\",\"nodeId\":\"A\",\"data\":{\"op\":\"REQ\",\"mode\":\"OTHER\"}}");
    }

    @Test
    public void pingRequiresNodeId() throws Exception {
        assertInvalid(
                "{\"v\":1,\"type\":\"PING\",\"data\":{\"op\":\"REQ\",\"mode\":\"HB\"}}");
    }

    @Test
    public void roundTripsSourceHeadSyncInBothDirections() throws Exception {
        UpstreamData.Sync timing =
                DefaultUpstreamData.sync(
                        new NodeId("A"),
                        UpstreamData.Stream.TIMING_DATA,
                        42L);
        UpstreamData.Sync race =
                DefaultUpstreamData.sync(
                        new NodeId("A"),
                        UpstreamData.Stream.RACE_DATA,
                        123L);

        UpstreamData.Sync decodedTiming =
                (UpstreamData.Sync) codec.decode(codec.encode(timing));
        assertSame(
                UpstreamData.Stream.TIMING_DATA,
                decodedTiming.stream());
        assertEquals(42L, decodedTiming.sequence());

        UpstreamData.Sync decodedRace =
                (UpstreamData.Sync) codec.decode(codec.encode(race));
        assertSame(
                UpstreamData.Stream.RACE_DATA,
                decodedRace.stream());
        assertEquals(123L, decodedRace.sequence());
    }

    @Test
    public void syncAllowsZeroAsEmptySourceHead() throws Exception {
        UpstreamData.Sync decoded =
                (UpstreamData.Sync) codec.decode(
                        bytes("{\"v\":1,\"type\":\"SYNC\",\"nodeId\":\"A\","
                                + "\"data\":{\"stream\":\"RACE_DATA\",\"seq\":0}}"));
        assertSame(UpstreamData.Stream.RACE_DATA, decoded.stream());
        assertEquals(0L, decoded.sequence());
    }

    @Test
    public void roundTripsRaceDataAck() throws Exception {
        UpstreamData.Ack ack =
                DefaultUpstreamData.ack(
                        new NodeId("A"),
                        UpstreamData.Stream.RACE_DATA,
                        123L);

        assertEquals(
                "{\"v\":1,\"type\":\"ACK\",\"nodeId\":\"A\","
                        + "\"data\":{\"stream\":\"RACE_DATA\",\"seq\":123}}",
                text(codec.encode(ack)));

        UpstreamData.Ack decoded =
                (UpstreamData.Ack) codec.decode(codec.encode(ack));
        assertSame(UpstreamData.Stream.RACE_DATA, decoded.stream());
        assertEquals(123L, decoded.sequence());
    }

    @Test
    public void ackDoesNotAcceptTimingDataStreamInV1() throws Exception {
        assertInvalid(
                "{\"v\":1,\"type\":\"ACK\",\"nodeId\":\"A\","
                        + "\"data\":{\"stream\":\"TIMING_DATA\",\"seq\":42}}");
    }

    @Test
    public void reqIsBidirectionalAndFromSequenceIsInclusive() throws Exception {
        UpstreamData.Req timingReq =
                DefaultUpstreamData.req(
                        new NodeId("A"),
                        UpstreamData.Stream.TIMING_DATA,
                        41L);
        UpstreamData.Req raceReq =
                DefaultUpstreamData.req(
                        new NodeId("A"),
                        UpstreamData.Stream.RACE_DATA,
                        121L);

        UpstreamData.Req decodedTiming =
                (UpstreamData.Req) codec.decode(codec.encode(timingReq));
        assertSame(
                UpstreamData.Stream.TIMING_DATA,
                decodedTiming.stream());
        assertEquals(41L, decodedTiming.fromSequence());

        UpstreamData.Req decodedRace =
                (UpstreamData.Req) codec.decode(codec.encode(raceReq));
        assertSame(UpstreamData.Stream.RACE_DATA, decodedRace.stream());
        assertEquals(121L, decodedRace.fromSequence());
    }

    @Test
    public void reqFromOneIsNormalSemanticReplayFromBeginning() throws Exception {
        UpstreamData.Req decoded =
                (UpstreamData.Req) codec.decode(
                        bytes("{\"v\":1,\"type\":\"REQ\",\"nodeId\":\"A\","
                                + "\"data\":{\"stream\":\"TIMING_DATA\",\"fromSeq\":1}}"));
        assertSame(UpstreamData.Stream.TIMING_DATA, decoded.stream());
        assertEquals(1L, decoded.fromSequence());
    }

    @Test
    public void raceDataSequenceIsTopLevelOnly() throws Exception {
        assertInvalid(
                "{\"v\":1,\"type\":\"SYNC\",\"nodeId\":\"A\",\"seq\":5,"
                        + "\"data\":{\"stream\":\"RACE_DATA\",\"seq\":5}}");
    }

    @Test
    public void reportsUnsupportedVersionAndTypeSeparately() throws Exception {
        try {
            codec.decode(
                    bytes("{\"v\":2,\"type\":\"PING\",\"data\":{\"op\":\"REQ\"}}"));
            fail("expected unsupported version");
        } catch (UpstreamDataCodec.CodecException expected) {
            assertSame(
                    UpstreamDataCodec.CodecException.Reason.UNSUPPORTED_VERSION,
                    expected.reason());
        }

        try {
            codec.decode(
                    bytes("{\"v\":1,\"type\":\"FUTURE\",\"data\":{}}"));
            fail("expected unsupported type");
        } catch (UpstreamDataCodec.CodecException expected) {
            assertSame(
                    UpstreamDataCodec.CodecException.Reason.UNSUPPORTED_MESSAGE_TYPE,
                    expected.reason());
        }
    }

    @Test
    public void rejectsDuplicateRaceDataKeysWithinOneMessage() throws Exception {
        assertInvalid(
                "{\"v\":1,\"type\":\"RACE_DATA\",\"nodeId\":\"A\","
                        + "\"seq\":1,\"data\":{\"teamAssignments\":["
                        + "{\"op\":\"SET\",\"regId\":\"R0001\",\"teamId\":\"0042\"},"
                        + "{\"op\":\"SET\",\"regId\":\"R0001\",\"teamId\":\"0043\"}]}}");
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

    private void assertInvalid(String json) throws Exception {
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
