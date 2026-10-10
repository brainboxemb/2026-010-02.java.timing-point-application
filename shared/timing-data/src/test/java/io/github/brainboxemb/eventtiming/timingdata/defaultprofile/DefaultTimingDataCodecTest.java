package io.github.brainboxemb.eventtiming.timingdata.defaultprofile;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DefaultTimingDataCodecTest {
    private static final TimingTimestamp EFFECTIVE =
            TimingTimestamp.parse("2026-09-30T20:01:39.12Z");
    private static final TimingTimestamp RECORDED =
            TimingTimestamp.parse("2026-09-30T20:01:45.456Z");

    private final DefaultTimingDataFactory factory = new DefaultTimingDataFactory();
    private final DefaultTimingDataCodec codec = new DefaultTimingDataCodec();

    @Test
    public void encodesAutomaticRegistrationInCanonicalMemberOrder() throws Exception {
        TimingData data = factory.createAutomaticRegistration(
                context(1L),
                new RegistrationId("registration-0042"));

        String json = new String(codec.encode(data), StandardCharsets.UTF_8);

        assertEquals(
                "{\"v\":1,"
                        + "\"nodeId\":\"A\","
                        + "\"seqNr\":1,"
                        + "\"locId\":7,"
                        + "\"recType\":\"AUTO_REG\","
                        + "\"time\":\"2026-09-30T20:01:39.12Z\","
                        + "\"regId\":\"registration-0042\","
                        + "\"code\":[\"ADD\"],"
                        + "\"recTime\":\"2026-09-30T20:01:45.456Z\"}",
                json);
        assertFalse(json.endsWith("\n"));
    }

    @Test
    public void preservesAutomaticProvenanceInRecordAndRecovery() throws Exception {
        TimingDataFactory.Context contextual = new TimingDataFactory.Context(
                new NodeId("A"), 1L, new LocationId(7),
                EFFECTIVE, RECORDED,
                TimingData.TagSource.API,
                TimingData.AutomaticTimeSource.NODE);
        TimingData.AutomaticRegistration record = factory.createAutomaticRegistration(
                contextual, new RegistrationId("RT-A-0001"));

        String json = new String(codec.encode(record), StandardCharsets.UTF_8);
        assertTrue(json.contains(
                "\"code\":[\"ADD\"],\"tagSrc\":\"API\",\"timeSrc\":\"NODE\""));
        TimingData.AutomaticRegistration recovered =
                (TimingData.AutomaticRegistration) codec.decode(codec.encode(record));
        assertSame(TimingData.TagSource.API, recovered.tagSource());
        assertSame(TimingData.AutomaticTimeSource.NODE, recovered.registrationTimeSource());
        assertTrue(new String(codec.encode(recovered), StandardCharsets.UTF_8)
                .contains("\"timeSrc\":\"NODE\""));

        // Old records are valid and must not acquire invented provenance.
        TimingData.AutomaticRegistration legacy =
                (TimingData.AutomaticRegistration) codec.decode(
                        codec.encode(factory.createAutomaticRegistration(
                                context(1L), new RegistrationId("RT-A-0001"))));
        assertEquals(null, legacy.tagSource());
        assertEquals(null, legacy.registrationTimeSource());

        // Partial or unknown attribution is invalid, not silently accepted.
        String oneMissing = json.replace(",\"timeSrc\":\"NODE\"", "");
        assertInvalid(json(oneMissing));
        assertInvalid(json(json.replace("\"tagSrc\":\"API\"", "\"tagSrc\":\"MYSTERY\"")));
        assertInvalid(json(json.replace("\"timeSrc\":\"NODE\"", "\"timeSrc\":\"CLOCK\"")));
    }

    @Test
    public void encodesAndDecodesManualRegistration() throws Exception {
        TimingData.ManualRegistration original =
                factory.createManualRegistration(
                        context(2L),
                        new RegistrationId("registration-\"0042"),
                        TimingData.ManualTimeSource.OPERATOR_ENTERED);

        String json = new String(codec.encode(original), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"recType\":\"MAN_REG\""));
        assertTrue(json.contains("\"code\":[\"ADD\",\"MAN\"]"));

        TimingData decoded = codec.decode(codec.encode(original));

        assertTrue(decoded instanceof TimingData.ManualRegistration);
        TimingData.ManualRegistration manual =
                (TimingData.ManualRegistration) decoded;
        assertCommon(manual, 2L);
        assertEquals(
                new RegistrationId("registration-\"0042"),
                manual.registrationId());
        assertSame(
                TimingData.ManualTimeSource.OPERATOR_ENTERED,
                manual.timeSource());
    }

    @Test
    public void systemAssignedManualTimeUsesAutoCode() throws Exception {
        TimingData.ManualRegistration original =
                factory.createManualRegistration(
                        context(2L),
                        new RegistrationId("registration-0042"),
                        TimingData.ManualTimeSource.AUTOMATIC);

        String json = new String(codec.encode(original), StandardCharsets.UTF_8);

        assertTrue(json.contains("\"recType\":\"MAN_REG\""));
        assertTrue(json.contains("\"code\":[\"ADD\",\"AUTO\"]"));
    }

    @Test
    public void encodesAndDecodesNodeOpenLifecycleRecord() throws Exception {
        TimingData.NodeOpen original =
                factory.createNodeOpen(
                        context(3L));

        String json = new String(codec.encode(original), StandardCharsets.UTF_8);

        assertEquals(
                "{\"v\":1,"
                        + "\"nodeId\":\"A\","
                        + "\"seqNr\":3,"
                        + "\"locId\":7,"
                        + "\"recType\":\"NODE_INFO\","
                        + "\"time\":\"2026-09-30T20:01:39.12Z\","
                        + "\"code\":[\"OPEN\"],"
                        + "\"recTime\":\"2026-09-30T20:01:45.456Z\"}",
                json);

        TimingData decoded = codec.decode(codec.encode(original));
        assertTrue(decoded instanceof TimingData.NodeOpen);
        assertCommon(decoded, 3L);
    }

    @Test
    public void encodesAndDecodesNodeCloseLifecycleRecord() throws Exception {
        TimingData.NodeClose original =
                factory.createNodeClose(
                        context(4L));

        TimingData decoded = codec.decode(codec.encode(original));

        assertTrue(decoded instanceof TimingData.NodeClose);
        assertCommon(decoded, 4L);
    }

    @Test
    public void lifecycleRecordRejectsRegistrationOnlyMembersAndInvalidCodes() throws Exception {
        assertInvalid(json(
                "{"
                        + "\"v\":1,"
                        + "\"nodeId\":\"A\","
                        + "\"seqNr\":3,"
                        + "\"locId\":7,"
                        + "\"recType\":\"NODE_INFO\","
                        + "\"time\":\"2026-09-30T20:01:39.12Z\","
                        + "\"regId\":\"registration-0042\","
                        + "\"code\":[\"OPEN\"],"
                        + "\"recTime\":\"2026-09-30T20:01:45.456Z\""
                        + "}"));
        assertInvalid(json(
                "{"
                        + "\"v\":1,"
                        + "\"nodeId\":\"A\","
                        + "\"seqNr\":4,"
                        + "\"locId\":7,"
                        + "\"recType\":\"NODE_INFO\","
                        + "\"time\":\"2026-09-30T20:01:39.12Z\","
                        + "\"code\":[\"ADD\"],"
                        + "\"recTime\":\"2026-09-30T20:01:45.456Z\""
                        + "}"));
    }

    @Test
    public void automaticRevocationUsesRevCodeAndRoundTrips() throws Exception {
        TimingData.AutomaticRegistration original =
                factory.createAutomaticRegistration(
                        context(5L),
                        new RegistrationId("registration-0042"),
                        TimingData.RegistrationAction.REV);

        String json =
                new String(
                        codec.encode(original),
                        StandardCharsets.UTF_8);
        assertTrue(
                json.contains(
                        "\"code\":[\"REV\"]"));

        TimingData.AutomaticRegistration decoded =
                (TimingData.AutomaticRegistration) codec.decode(
                        codec.encode(original));
        assertSame(
                TimingData.RegistrationAction.REV,
                decoded.action());
        assertCommon(
                decoded,
                5L);
    }

    @Test
    public void manualRevocationRepeatsTimeSourceSubcode() throws Exception {
        TimingData.ManualRegistration original =
                factory.createManualRegistration(
                        context(6L),
                        new RegistrationId("registration-0042"),
                        TimingData.ManualTimeSource.OPERATOR_ENTERED,
                        TimingData.RegistrationAction.REV);

        String json =
                new String(
                        codec.encode(original),
                        StandardCharsets.UTF_8);
        assertTrue(
                json.contains(
                        "\"code\":[\"REV\",\"MAN\"]"));

        TimingData.ManualRegistration decoded =
                (TimingData.ManualRegistration) codec.decode(
                        codec.encode(original));
        assertSame(
                TimingData.RegistrationAction.REV,
                decoded.action());
        assertSame(
                TimingData.ManualTimeSource.OPERATOR_ENTERED,
                decoded.timeSource());
        assertCommon(
                decoded,
                6L);
    }

    @Test
    public void decodesAutomaticRegistration() throws Exception {
        TimingData decoded = codec.decode(json(
                "{"
                        + "\"v\":1,"
                        + "\"nodeId\":\"A\","
                        + "\"seqNr\":1,"
                        + "\"locId\":7,"
                        + "\"recType\":\"AUTO_REG\","
                        + "\"time\":\"2026-09-30T20:01:39.12Z\","
                        + "\"regId\":\"registration-0042\","
                        + "\"code\":[\"ADD\"],"
                        + "\"recTime\":\"2026-09-30T20:01:45.456Z\""
                        + "}"));

        assertTrue(decoded instanceof TimingData.AutomaticRegistration);
        TimingData.AutomaticRegistration automatic =
                (TimingData.AutomaticRegistration) decoded;
        assertCommon(automatic, 1L);
        assertEquals(
                new RegistrationId("registration-0042"),
                automatic.registrationId());
    }

    @Test
    public void readerIgnoresAdditionalMembersAndCodeOrder() throws Exception {
        TimingData decoded = codec.decode(json(
                "{"
                        + "\"extra\":{\"future\":[1,2,3]},"
                        + "\"regId\":\"registration-0042\","
                        + "\"recTime\":\"2026-09-30T20:01:45.456Z\","
                        + "\"v\":1,"
                        + "\"locId\":7,"
                        + "\"seqNr\":2,"
                        + "\"code\":[\"AUTO\",\"ADD\"],"
                        + "\"recType\":\"MAN_REG\","
                        + "\"time\":\"2026-09-30T20:01:39.12Z\","
                        + "\"nodeId\":\"A\""
                        + "}"));

        assertTrue(decoded instanceof TimingData.ManualRegistration);
        assertSame(
                TimingData.ManualTimeSource.AUTOMATIC,
                ((TimingData.ManualRegistration) decoded).timeSource());
    }

    @Test
    public void writerRetainsRequiredFractionalZeroes() throws Exception {
        TimingData data =
                factory.createAutomaticRegistration(
                        new TimingDataFactory.Context(
                                new NodeId("A"),
                                8L,
                                new LocationId(7),
                                TimingTimestamp.parse(
                                        "2026-09-30T20:01:39.9Z"),
                                TimingTimestamp.parse(
                                        "2026-09-30T20:01:45.1Z")),
                        new RegistrationId(
                                "registration-0042"));

        String json =
                new String(
                        codec.encode(data),
                        StandardCharsets.UTF_8);

        assertTrue(
                json.contains(
                        "\"time\":\"2026-09-30T20:01:39.90Z\""));
        assertTrue(
                json.contains(
                        "\"recTime\":\"2026-09-30T20:01:45.100Z\""));
    }

    @Test
    public void writerRejectsFinerThanProfilePrecision() throws Exception {
        TimingData data =
                factory.createAutomaticRegistration(
                        new TimingDataFactory.Context(
                                new NodeId("A"),
                                8L,
                                new LocationId(7),
                                TimingTimestamp.parse(
                                        "2026-09-30T20:01:39.129Z"),
                                RECORDED),
                        new RegistrationId(
                                "registration-0042"));

        try {
            codec.encode(data);
            fail("expected precision failure");
        } catch (TimingDataCodec.CodecException expected) {
            assertSame(
                    TimingDataCodec.CodecException.Reason.ENCODE_FAILURE,
                    expected.reason());
            assertTrue(
                    expected.getMessage().contains(
                            "centisecond"));
        }
    }

    @Test
    public void readerStillAcceptsFinerTimestampInput() throws Exception {
        TimingData decoded =
                codec.decode(
                        json(
                                "{"
                                        + "\"v\":1,"
                                        + "\"nodeId\":\"A\","
                                        + "\"seqNr\":8,"
                                        + "\"locId\":7,"
                                        + "\"recType\":\"AUTO_REG\","
                                        + "\"time\":\"2026-09-30T20:01:39.123456789Z\","
                                        + "\"regId\":\"registration-0042\","
                                        + "\"code\":[\"ADD\"],"
                                        + "\"recTime\":\"2026-09-30T20:01:45.456789Z\""
                                        + "}"));

        assertEquals(
                TimingTimestamp.parse(
                        "2026-09-30T20:01:39.123456789Z"),
                decoded.effectiveTime());
        assertEquals(
                TimingTimestamp.parse(
                        "2026-09-30T20:01:45.456789Z"),
                decoded.recordedAt());
    }

    @Test
    public void unsupportedVersionIsReportedSeparately() throws Exception {
        try {
            codec.decode(json("{\"v\":2}"));
            fail("expected unsupported version");
        } catch (TimingDataCodec.CodecException expected) {
            assertSame(
                    TimingDataCodec.CodecException.Reason.UNSUPPORTED_VERSION,
                    expected.reason());
            assertEquals(Integer.valueOf(2), expected.version());
        }
    }

    @Test
    public void unknownV1RecordTypeRetainsReadableCommonEnvelope() throws Exception {
        try {
            codec.decode(json(
                    "{"
                            + "\"v\":1,"
                            + "\"nodeId\":\"A\","
                            + "\"seqNr\":9,"
                            + "\"locId\":7,"
                            + "\"recType\":\"FUTURE_RECORD\","
                            + "\"time\":\"2026-09-30T20:01:39.12Z\","
                            + "\"recTime\":\"2026-09-30T20:01:45.456Z\""
                            + "}"));
            fail("expected unsupported record type");
        } catch (TimingDataCodec.CodecException expected) {
            assertSame(
                    TimingDataCodec.CodecException.Reason.UNSUPPORTED_RECORD_TYPE,
                    expected.reason());
            assertEquals(
                    new TimingData.RecordKey(new NodeId("A"), 9L),
                    expected.key());
            assertEquals(new LocationId(7), expected.locationId());
            assertEquals("FUTURE_RECORD", expected.recordType());
            assertEquals(EFFECTIVE, expected.effectiveTime());
            assertEquals(RECORDED, expected.recordedAt());
        }
    }

    @Test
    public void malformedOrSemanticallyInvalidRecordsAreInvalidData() throws Exception {
        assertInvalid(json("{\"v\":1"));
        assertInvalid(json(
                "{"
                        + "\"v\":1,"
                        + "\"nodeId\":\"A\","
                        + "\"seqNr\":1,"
                        + "\"locId\":7,"
                        + "\"recType\":\"AUTO_REG\","
                        + "\"time\":\"2026-09-30T20:01:39.12Z\","
                        + "\"regId\":\"registration-0042\","
                        + "\"code\":[\"REV\",\"AUTO\"],"
                        + "\"recTime\":\"2026-09-30T20:01:45.456Z\""
                        + "}"));
        assertInvalid(json(
                "{"
                        + "\"v\":1,"
                        + "\"v\":1,"
                        + "\"nodeId\":\"A\""
                        + "}"));
        assertInvalid(json(
                "{"
                        + "\"v\":1,"
                        + "\"nodeId\":\"A\","
                        + "\"seqNr\":1,"
                        + "\"locId\":7,"
                        + "\"recType\":\"MAN_REG\","
                        + "\"time\":\"2026-09-30T20:01:39.12Z\","
                        + "\"regId\":\"registration-0042\","
                        + "\"code\":[\"ADD\",\"AUTO\",\"MAN\"],"
                        + "\"recTime\":\"2026-09-30T20:01:45.456Z\""
                        + "}"));
    }

    @Test
    public void rejectsDuplicateCode() throws Exception {
        assertInvalid(json(
                "{"
                        + "\"v\":1,"
                        + "\"nodeId\":\"A\","
                        + "\"seqNr\":1,"
                        + "\"locId\":7,"
                        + "\"recType\":\"MAN_REG\","
                        + "\"time\":\"2026-09-30T20:01:39.12Z\","
                        + "\"regId\":\"registration-0042\","
                        + "\"code\":[\"ADD\",\"ADD\"],"
                        + "\"recTime\":\"2026-09-30T20:01:45.456Z\""
                        + "}"));
    }

    @Test
    public void rejectsUtf8Bom() throws Exception {
        byte[] body = json("{\"v\":1}");
        byte[] encoded = new byte[body.length + 3];
        encoded[0] = (byte) 0xef;
        encoded[1] = (byte) 0xbb;
        encoded[2] = (byte) 0xbf;
        System.arraycopy(body, 0, encoded, 3, body.length);

        assertInvalid(encoded);
    }

    private static TimingDataFactory.Context context(long sequence) {
        return new TimingDataFactory.Context(
                new NodeId("A"),
                sequence,
                new LocationId(7),
                EFFECTIVE,
                RECORDED);
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertCommon(TimingData data, long sequence) {
        assertEquals(new NodeId("A"), data.timingNodeId());
        assertEquals(sequence, data.sequenceNumber());
        assertEquals(new LocationId(7), data.locationId());
        assertEquals(EFFECTIVE, data.effectiveTime());
        assertEquals(RECORDED, data.recordedAt());
    }

    private void assertInvalid(byte[] encoded) throws Exception {
        try {
            codec.decode(encoded);
            fail("expected invalid data");
        } catch (TimingDataCodec.CodecException expected) {
            assertSame(
                    TimingDataCodec.CodecException.Reason.INVALID_DATA,
                    expected.reason());
        }
    }
}
