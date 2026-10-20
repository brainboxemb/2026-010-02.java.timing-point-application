package io.github.brainboxemb.eventtiming.timingdata.defaultprofile;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class DefaultTimingDataCodecTest {
    private static final TimingTimestamp EFFECTIVE =
            TimingTimestamp.parse("2026-09-30T20:01:39.12Z");
    private static final TimingTimestamp RECORDED =
            TimingTimestamp.parse("2026-09-30T20:01:45.45Z");

    private final DefaultTimingDataCodec codec = new DefaultTimingDataCodec();

    @Test
    public void encodesAutomaticRegistrationInCanonicalMemberOrder() throws Exception {
        TimingRecord record = automatic(1L, "A0042");

        String json = text(codec.encode(record));

        assertEquals(
                "{"v":1,"
                        + ""nodeId":"A","
                        + ""seqNr":1,"
                        + ""locId":7,"
                        + ""recType":"AUTO_REG","
                        + ""time":"2026-09-30T20:01:39.12Z","
                        + ""regId":"A0042","
                        + ""code":["ADD"],"
                        + ""recTime":"2026-09-30T20:01:45.450Z"}",
                json);
        assertFalse(json.endsWith("\n"));
    }

    @Test
    public void preservesAutomaticProvenanceInRecordAndRecovery() throws Exception {
        TimingRecord record = TimingRecord.automatic(
                'A',
                1L,
                7,
                effectiveMillis(),
                recordedMillis(),
                registrationId("A0001"),
                TimingRecord.Action.ADD,
                TimingRecord.TagSource.API,
                TimingRecord.AutomaticTimeSource.NODE);

        String json = text(codec.encode(record));
        assertTrue(json.contains(
                ""code":["ADD"],"tagSrc":"API","timeSrc":"NODE""));

        TimingRecord recovered = codec.decode(codec.encode(record));
        assertSame(TimingRecord.TagSource.API, recovered.tagSource());
        assertSame(
                TimingRecord.AutomaticTimeSource.NODE,
                recovered.automaticTimeSource());
        assertTrue(text(codec.encode(recovered)).contains(""timeSrc":"NODE""));

        TimingRecord legacy = codec.decode(codec.encode(automatic(1L, "A0001")));
        assertNull(legacy.tagSource());
        assertNull(legacy.automaticTimeSource());

        assertInvalid(json.replace(","timeSrc":"NODE"", ""));
        assertInvalid(json.replace(""tagSrc":"API"", ""tagSrc":"MYSTERY""));
        assertInvalid(json.replace(""timeSrc":"NODE"", ""timeSrc":"CLOCK""));
    }

    @Test
    public void encodesAndDecodesManualRegistration() throws Exception {
        TimingRecord original = manual(
                2L,
                "A0042",
                TimingRecord.Action.ADD,
                TimingRecord.ManualTimeSource.OPERATOR_ENTERED);

        String json = text(codec.encode(original));
        assertTrue(json.contains(""recType":"MAN_REG""));
        assertTrue(json.contains(""code":["ADD","MAN"]"));

        TimingRecord decoded = codec.decode(codec.encode(original));

        assertCommon(decoded, 2L);
        assertSame(TimingRecord.Kind.MANUAL_REGISTRATION, decoded.kind());
        assertEquals(registrationId("A0042"), decoded.registrationId());
        assertSame(
                TimingRecord.ManualTimeSource.OPERATOR_ENTERED,
                decoded.manualTimeSource());
    }

    @Test
    public void systemAssignedManualTimeUsesAutoCode() throws Exception {
        TimingRecord original = manual(
                2L,
                "A0042",
                TimingRecord.Action.ADD,
                TimingRecord.ManualTimeSource.AUTOMATIC);

        String json = text(codec.encode(original));

        assertTrue(json.contains(""recType":"MAN_REG""));
        assertTrue(json.contains(""code":["ADD","AUTO"]"));
    }

    @Test
    public void encodesAndDecodesNodeOpenLifecycleRecord() throws Exception {
        TimingRecord original = opened(3L);

        String json = text(codec.encode(original));

        assertEquals(
                "{"v":1,"
                        + ""nodeId":"A","
                        + ""seqNr":3,"
                        + ""locId":7,"
                        + ""recType":"NODE_INFO","
                        + ""time":"2026-09-30T20:01:39.12Z","
                        + ""code":["OPEN"],"
                        + ""recTime":"2026-09-30T20:01:45.450Z"}",
                json);

        TimingRecord decoded = codec.decode(codec.encode(original));
        assertSame(TimingRecord.Kind.NODE_OPEN, decoded.kind());
        assertCommon(decoded, 3L);
    }

    @Test
    public void encodesAndDecodesNodeCloseLifecycleRecord() throws Exception {
        TimingRecord decoded = codec.decode(codec.encode(closed(4L)));

        assertSame(TimingRecord.Kind.NODE_CLOSE, decoded.kind());
        assertCommon(decoded, 4L);
    }

    @Test
    public void lifecycleRecordRejectsRegistrationOnlyMembersAndInvalidCodes() throws Exception {
        assertInvalid(
                "{"
                        + ""v":1,"
                        + ""nodeId":"A","
                        + ""seqNr":3,"
                        + ""locId":7,"
                        + ""recType":"NODE_INFO","
                        + ""time":"2026-09-30T20:01:39.120Z","
                        + ""regId":"A0042","
                        + ""code":["OPEN"],"
                        + ""recTime":"2026-09-30T20:01:45.456Z""
                        + "}");
        assertInvalid(
                "{"
                        + ""v":1,"
                        + ""nodeId":"A","
                        + ""seqNr":4,"
                        + ""locId":7,"
                        + ""recType":"NODE_INFO","
                        + ""time":"2026-09-30T20:01:39.120Z","
                        + ""code":["ADD"],"
                        + ""recTime":"2026-09-30T20:01:45.456Z""
                        + "}");
    }

    @Test
    public void automaticRevocationUsesRevCodeAndRoundTrips() throws Exception {
        TimingRecord original = TimingRecord.automatic(
                'A',
                5L,
                7,
                effectiveMillis(),
                recordedMillis(),
                registrationId("A0042"),
                TimingRecord.Action.REV,
                null,
                null);

        String json = text(codec.encode(original));
        assertTrue(json.contains(""code":["REV"]"));

        TimingRecord decoded = codec.decode(codec.encode(original));
        assertSame(TimingRecord.Action.REV, decoded.action());
        assertSame(TimingRecord.Kind.AUTOMATIC_REGISTRATION, decoded.kind());
        assertCommon(decoded, 5L);
    }

    @Test
    public void manualRevocationRepeatsTimeSourceSubcode() throws Exception {
        TimingRecord original = manual(
                6L,
                "A0042",
                TimingRecord.Action.REV,
                TimingRecord.ManualTimeSource.OPERATOR_ENTERED);

        String json = text(codec.encode(original));
        assertTrue(json.contains(""code":["REV","MAN"]"));

        TimingRecord decoded = codec.decode(codec.encode(original));
        assertSame(TimingRecord.Action.REV, decoded.action());
        assertSame(
                TimingRecord.ManualTimeSource.OPERATOR_ENTERED,
                decoded.manualTimeSource());
        assertCommon(decoded, 6L);
    }

    @Test
    public void decodesAutomaticRegistration() throws Exception {
        TimingRecord decoded = codec.decode(json(
                "{"
                        + ""v":1,"
                        + ""nodeId":"A","
                        + ""seqNr":1,"
                        + ""locId":7,"
                        + ""recType":"AUTO_REG","
                        + ""time":"2026-09-30T20:01:39.120Z","
                        + ""regId":"A0042","
                        + ""code":["ADD"],"
                        + ""recTime":"2026-09-30T20:01:45.450Z""
                        + "}"));

        assertSame(TimingRecord.Kind.AUTOMATIC_REGISTRATION, decoded.kind());
        assertCommon(decoded, 1L);
        assertEquals(registrationId("A0042"), decoded.registrationId());
        assertSame(TimingRecord.Action.ADD, decoded.action());
    }

    @Test
    public void readerIgnoresAdditionalMembersAndCodeOrder() throws Exception {
        TimingRecord decoded = codec.decode(json(
                "{"
                        + ""extra":{"future":[1,2,3]},"
                        + ""regId":"A0042","
                        + ""recTime":"2026-09-30T20:01:45.456Z","
                        + ""v":1,"
                        + ""locId":7,"
                        + ""seqNr":2,"
                        + ""code":["AUTO","ADD"],"
                        + ""recType":"MAN_REG","
                        + ""time":"2026-09-30T20:01:39.120Z","
                        + ""nodeId":"A""
                        + "}"));

        assertSame(TimingRecord.Kind.MANUAL_REGISTRATION, decoded.kind());
        assertSame(
                TimingRecord.ManualTimeSource.AUTOMATIC,
                decoded.manualTimeSource());
    }

    @Test
    public void writerRetainsRequiredFractionalZeroes() throws Exception {
        TimingRecord record = TimingRecord.automatic(
                'A',
                8L,
                7,
                Instant.parse("2026-09-30T20:01:39.9Z").toEpochMilli(),
                Instant.parse("2026-09-30T20:01:45.1Z").toEpochMilli(),
                registrationId("A0042"),
                TimingRecord.Action.ADD,
                null,
                null);

        String json = text(codec.encode(record));

        assertTrue(json.contains(""time":"2026-09-30T20:01:39.90Z""));
        assertTrue(json.contains(""recTime":"2026-09-30T20:01:45.100Z""));
    }

    @Test
    public void recordStoresFinerInputAtMillisecondPrecisionWithoutChangingV1TextShape()
            throws Exception {
        long effective = Instant.parse("2026-09-30T20:01:39.129Z").toEpochMilli();
        TimingRecord record = TimingRecord.automatic(
                'A',
                8L,
                7,
                effective,
                recordedMillis(),
                registrationId("A0042"),
                TimingRecord.Action.ADD,
                null,
                null);

        assertEquals(effective, record.effectiveTimeMillis());
        assertTrue(
                text(codec.encode(record))
                        .contains(""time":"2026-09-30T20:01:39.12Z""));
    }

    @Test
    public void readerStillAcceptsFinerTimestampInput() throws Exception {
        TimingRecord decoded =
                codec.decode(
                        json(
                                "{"
                                        + ""v":1,"
                                        + ""nodeId":"A","
                                        + ""seqNr":8,"
                                        + ""locId":7,"
                                        + ""recType":"AUTO_REG","
                                        + ""time":"2026-09-30T20:01:39.123456789Z","
                                        + ""regId":"A0042","
                                        + ""code":["ADD"],"
                                        + ""recTime":"2026-09-30T20:01:45.456789Z""
                                        + "}"));

        assertEquals(
                Instant.parse("2026-09-30T20:01:39.123Z").toEpochMilli(),
                decoded.effectiveTimeMillis());
        assertEquals(
                Instant.parse("2026-09-30T20:01:45.456Z").toEpochMilli(),
                decoded.recordedAtMillis());
    }

    @Test
    public void unsupportedVersionIsReportedSeparately() throws Exception {
        try {
            codec.decode(json("{"v":2}"));
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
                            + ""v":1,"
                            + ""nodeId":"A","
                            + ""seqNr":9,"
                            + ""locId":7,"
                            + ""recType":"FUTURE_RECORD","
                            + ""time":"2026-09-30T20:01:39.120Z","
                            + ""recTime":"2026-09-30T20:01:45.450Z""
                            + "}"));
            fail("expected unsupported record type");
        } catch (TimingDataCodec.CodecException expected) {
            assertSame(
                    TimingDataCodec.CodecException.Reason.UNSUPPORTED_RECORD_TYPE,
                    expected.reason());
            assertEquals(
                    new TimingRecord.RecordKey('A', 9L),
                    expected.key());
            assertEquals(7, expected.locationId().value());
            assertEquals("FUTURE_RECORD", expected.recordType());
            assertEquals(EFFECTIVE, expected.effectiveTime());
            assertEquals(RECORDED, expected.recordedAt());
        }
    }

    @Test
    public void malformedOrSemanticallyInvalidRecordsAreInvalidData() throws Exception {
        assertInvalid("{"v":1");
        assertInvalid(
                "{"
                        + ""v":1,"
                        + ""nodeId":"A","
                        + ""seqNr":1,"
                        + ""locId":7,"
                        + ""recType":"AUTO_REG","
                        + ""time":"2026-09-30T20:01:39.120Z","
                        + ""regId":"A0042","
                        + ""code":["REV","AUTO"],"
                        + ""recTime":"2026-09-30T20:01:45.456Z""
                        + "}");
        assertInvalid(
                "{"
                        + ""v":1,"
                        + ""v":1,"
                        + ""nodeId":"A""
                        + "}");
        assertInvalid(
                "{"
                        + ""v":1,"
                        + ""nodeId":"A","
                        + ""seqNr":1,"
                        + ""locId":7,"
                        + ""recType":"MAN_REG","
                        + ""time":"2026-09-30T20:01:39.120Z","
                        + ""regId":"A0042","
                        + ""code":["ADD","AUTO","MAN"],"
                        + ""recTime":"2026-09-30T20:01:45.456Z""
                        + "}");
    }

    @Test
    public void rejectsDuplicateCode() throws Exception {
        assertInvalid(
                "{"
                        + ""v":1,"
                        + ""nodeId":"A","
                        + ""seqNr":1,"
                        + ""locId":7,"
                        + ""recType":"MAN_REG","
                        + ""time":"2026-09-30T20:01:39.120Z","
                        + ""regId":"A0042","
                        + ""code":["ADD","ADD"],"
                        + ""recTime":"2026-09-30T20:01:45.456Z""
                        + "}");
    }

    @Test
    public void rejectsUtf8Bom() throws Exception {
        byte[] body = json("{"v":1}");
        byte[] encoded = new byte[body.length + 3];
        encoded[0] = (byte) 0xef;
        encoded[1] = (byte) 0xbb;
        encoded[2] = (byte) 0xbf;
        System.arraycopy(body, 0, encoded, 3, body.length);

        assertInvalid(encoded);
    }

    private static TimingRecord automatic(long sequence, String registrationId) {
        return TimingRecord.automatic(
                'A',
                sequence,
                7,
                effectiveMillis(),
                recordedMillis(),
                registrationId(registrationId),
                TimingRecord.Action.ADD,
                null,
                null);
    }

    private static TimingRecord manual(
            long sequence,
            String registrationId,
            TimingRecord.Action action,
            TimingRecord.ManualTimeSource timeSource) {
        return TimingRecord.manual(
                'A',
                sequence,
                7,
                effectiveMillis(),
                recordedMillis(),
                registrationId(registrationId),
                action,
                timeSource);
    }

    private static TimingRecord opened(long sequence) {
        return TimingRecord.opened(
                'A',
                sequence,
                7,
                effectiveMillis(),
                recordedMillis());
    }

    private static TimingRecord closed(long sequence) {
        return TimingRecord.closed(
                'A',
                sequence,
                7,
                effectiveMillis(),
                recordedMillis());
    }

    private static int registrationId(String value) {
        return new RegistrationId(value).packedValue();
    }

    private static long effectiveMillis() {
        return EFFECTIVE.instant().toEpochMilli();
    }

    private static long recordedMillis() {
        return RECORDED.instant().toEpochMilli();
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertCommon(TimingRecord record, long sequence) {
        assertEquals('A', record.nodeId());
        assertEquals(sequence, record.sequenceNumber());
        assertEquals(7, record.locationId());
        assertEquals(effectiveMillis(), record.effectiveTimeMillis());
        assertEquals(recordedMillis(), record.recordedAtMillis());
    }

    private void assertInvalid(String encoded) throws Exception {
        assertInvalid(json(encoded));
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
