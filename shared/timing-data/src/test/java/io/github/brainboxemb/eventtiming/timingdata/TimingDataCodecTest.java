package io.github.brainboxemb.eventtiming.timingdata;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class TimingDataCodecTest {
    private static final TimingTimestamp EFFECTIVE =
            TimingTimestamp.parse("2026-09-30T20:01:39.123000000Z");
    private static final TimingTimestamp RECORDED =
            TimingTimestamp.parse("2026-09-30T20:01:40.000000000Z");

    @Test
    public void unsupportedVersionCarriesVersionWithoutPretendingV1Envelope() {
        TimingDataCodec.CodecException failure =
                TimingDataCodec.CodecException.unsupportedVersion(
                        2,
                        "unsupported version");

        assertSame(
                TimingDataCodec.CodecException.Reason.UNSUPPORTED_VERSION,
                failure.reason());
        assertEquals(Integer.valueOf(2), failure.version());
        assertNull(failure.key());
        assertNull(failure.locationId());
        assertNull(failure.recordType());
        assertNull(failure.effectiveTime());
        assertNull(failure.recordedAt());
    }

    @Test
    public void unsupportedV1RecordTypeCarriesReadableCommonEnvelope() {
        TimingData.RecordKey key =
                new TimingData.RecordKey(new TimingDataTypes.NodeId("A"), 7);

        TimingDataCodec.CodecException failure =
                TimingDataCodec.CodecException.unsupportedRecordType(
                        1,
                        key,
                        new TimingDataTypes.LocationId(12),
                        "FUTURE_RECORD",
                        EFFECTIVE,
                        RECORDED,
                        "unsupported record type");

        assertSame(
                TimingDataCodec.CodecException.Reason.UNSUPPORTED_RECORD_TYPE,
                failure.reason());
        assertEquals(Integer.valueOf(1), failure.version());
        assertEquals(key, failure.key());
        assertEquals(new TimingDataTypes.LocationId(12), failure.locationId());
        assertEquals("FUTURE_RECORD", failure.recordType());
        assertEquals(EFFECTIVE, failure.effectiveTime());
        assertEquals(RECORDED, failure.recordedAt());
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedRecordTypeRequiresKey() {
        TimingDataCodec.CodecException.unsupportedRecordType(
                1,
                null,
                new TimingDataTypes.LocationId(12),
                "FUTURE_RECORD",
                EFFECTIVE,
                RECORDED,
                "unsupported record type");
    }
}
