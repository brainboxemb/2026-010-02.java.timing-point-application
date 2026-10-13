package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
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
        TimingDataFactory factory = new DefaultTimingDataFactory();
        TimingData record = factory.createAutomaticRegistration(
                new TimingDataFactory.Context(
                        nodeId,
                        42L,
                        new LocationId(1),
                        TimingTimestamp.parse("2026-10-13T08:00:00.12Z"),
                        TimingTimestamp.parse("2026-10-13T08:00:00.123Z")),
                new RegistrationId("RT-A-0042"));

        UpstreamData.TimingDataTransfer transfer =
                DefaultUpstreamData.timingData(
                        nodeId,
                        Collections.singletonList(record));

        String json = text(codec.encode(transfer));
        assertTrue(json.startsWith(
                "{\"v\":1,\"type\":\"TIMING_DATA\",\"nodeId\":\"A\",\"data\":[{"));
        assertTrue(json.contains("\"seqNr\":42"));
        assertTrue(json.contains("\"regId\":\"RT-A-0042\""));

        UpstreamData.TimingDataTransfer decoded =
                (UpstreamData.TimingDataTransfer) codec.decode(bytes(json));
        assertEquals(nodeId, decoded.timingNodeId());
        assertEquals(1, decoded.records().size());
        assertEquals(42L, decoded.records().get(0).sequenceNumber());
        assertEquals(nodeId, decoded.records().get(0).timingNodeId());
    }

    @Test
    public void roundTripsRaceDataSnapshot() throws Exception {
        UpstreamData.RaceData original =
                DefaultUpstreamData.raceData(
                        new NodeId("A"),
                        12L,
                        Arrays.asList(
                                new UpstreamData.RaceAssignment(
                                        new RegistrationId("RT-R-0001"),
                                        new TeamId("0042")),
                                new UpstreamData.RaceAssignment(
                                        new RegistrationId("RT-R-0002"),
                                        new TeamId("0043"))));

        String json = text(codec.encode(original));
        assertTrue(json.contains("\"dataset\":\"RACE_DATA\""));
        assertTrue(json.contains("\"rev\":12"));

        UpstreamData.RaceData decoded =
                (UpstreamData.RaceData) codec.decode(bytes(json));
        assertEquals(12L, decoded.revision());
        assertEquals(original.assignments(), decoded.assignments());
    }

    @Test
    public void roundTripsStageStartTimesWithOptionalDate() throws Exception {
        UpstreamData.StageStartTimes original =
                DefaultUpstreamData.stageStartTimes(
                        new NodeId("A"),
                        7L,
                        Arrays.asList(
                                new UpstreamData.StageStartDefinition(
                                        new TeamId("0042"),
                                        LocalTime.of(23, 59, 50, 120000000)),
                                new UpstreamData.StageStartDefinition(
                                        new TeamId("0043"),
                                        LocalTime.of(0, 2, 0),
                                        LocalDate.of(2026, 10, 14))));

        String json = text(codec.encode(original));
        assertTrue(json.contains("\"startTime\":\"23:59:50.12\""));
        assertTrue(json.contains("\"startTime\":\"00:02:00\""));
        assertTrue(json.contains("\"startDate\":\"2026-10-14\""));

        UpstreamData.StageStartTimes decoded =
                (UpstreamData.StageStartTimes) codec.decode(bytes(json));
        assertEquals(original.starts(), decoded.starts());
    }

    @Test
    public void rejectsStageStartTimeFinerThanCentisecondOnEncode() throws Exception {
        UpstreamData.StageStartTimes data =
                DefaultUpstreamData.stageStartTimes(
                        new NodeId("A"),
                        1L,
                        Collections.singletonList(
                                new UpstreamData.StageStartDefinition(
                                        new TeamId("0042"),
                                        LocalTime.of(12, 0, 0, 123000000))));

        try {
            codec.encode(data);
            fail("expected encode failure");
        } catch (UpstreamDataCodec.CodecException expected) {
            assertSame(
                    UpstreamDataCodec.CodecException.Reason.ENCODE_FAILURE,
                    expected.reason());
        }
    }

    @Test
    public void roundTripsHeartbeatAndManualPing() throws Exception {
        UpstreamData.Ping heartbeat =
                DefaultUpstreamData.ping(
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        "p-0001");

        assertEquals(
                "{\"v\":1,\"type\":\"PING\",\"data\":{\"op\":\"REQ\",\"mode\":\"HB\",\"pingId\":\"p-0001\"}}",
                text(codec.encode(heartbeat)));

        UpstreamData.Ping decodedHeartbeat =
                (UpstreamData.Ping) codec.decode(
                        codec.encode(heartbeat));
        assertSame(
                UpstreamData.PingOperation.REQ,
                decodedHeartbeat.operation());
        assertSame(
                UpstreamData.PingMode.HB,
                decodedHeartbeat.mode());
        assertEquals("p-0001", decodedHeartbeat.pingId());

        UpstreamData.Ping manual =
                (UpstreamData.Ping) codec.decode(
                        bytes("{\"v\":1,\"type\":\"PING\",\"data\":{\"op\":\"ANS\"}}"));
        assertSame(UpstreamData.PingOperation.ANS, manual.operation());
        assertNull(manual.mode());
        assertNull(manual.pingId());
    }

    @Test
    public void rejectsUnknownPingModeRatherThanTreatingItAsManual() throws Exception {
        assertInvalid(
                "{\"v\":1,\"type\":\"PING\",\"data\":{\"op\":\"REQ\",\"mode\":\"OTHER\"}}");
    }

    @Test
    public void roundTripsSyncProgressAndReferenceRevisions() throws Exception {
        UpstreamData.Sync original =
                DefaultUpstreamData.sync(
                        new NodeId("A"),
                        42L,
                        Long.valueOf(12L),
                        Long.valueOf(7L));

        String json = text(codec.encode(original));
        assertTrue(json.contains("\"timingSeq\":42"));
        assertTrue(json.contains("\"RACE_DATA\":12"));
        assertTrue(json.contains("\"STAGE_START_TIMES\":7"));

        UpstreamData.Sync decoded =
                (UpstreamData.Sync) codec.decode(bytes(json));
        assertEquals(42L, decoded.timingSequence());
        assertEquals(Long.valueOf(12L), decoded.raceDataRevision());
        assertEquals(Long.valueOf(7L), decoded.stageStartTimesRevision());
    }

    @Test
    public void reportsUnsupportedVersionTypeAndDatasetSeparately() throws Exception {
        try {
            codec.decode(bytes("{\"v\":2,\"type\":\"PING\",\"data\":{\"op\":\"REQ\"}}"));
            fail("expected unsupported version");
        } catch (UpstreamDataCodec.CodecException expected) {
            assertSame(
                    UpstreamDataCodec.CodecException.Reason.UNSUPPORTED_VERSION,
                    expected.reason());
        }

        try {
            codec.decode(bytes("{\"v\":1,\"type\":\"FUTURE\",\"data\":{}}"));
            fail("expected unsupported type");
        } catch (UpstreamDataCodec.CodecException expected) {
            assertSame(
                    UpstreamDataCodec.CodecException.Reason.UNSUPPORTED_MESSAGE_TYPE,
                    expected.reason());
        }

        try {
            codec.decode(bytes(
                    "{\"v\":1,\"type\":\"REFERENCE_DATA\",\"nodeId\":\"A\","
                            + "\"data\":{\"dataset\":\"FUTURE\",\"rev\":1,\"items\":[]}}"));
            fail("expected unsupported dataset");
        } catch (UpstreamDataCodec.CodecException expected) {
            assertSame(
                    UpstreamDataCodec.CodecException.Reason.UNSUPPORTED_DATASET,
                    expected.reason());
        }
    }

    @Test
    public void rejectsDuplicateReferenceAssignments() throws Exception {
        assertInvalid(
                "{\"v\":1,\"type\":\"REFERENCE_DATA\",\"nodeId\":\"A\","
                        + "\"data\":{\"dataset\":\"RACE_DATA\",\"rev\":1,\"items\":["
                        + "{\"regId\":\"RT-R-0001\",\"teamId\":\"0042\"},"
                        + "{\"regId\":\"RT-R-0002\",\"teamId\":\"0042\"}]}}");
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
