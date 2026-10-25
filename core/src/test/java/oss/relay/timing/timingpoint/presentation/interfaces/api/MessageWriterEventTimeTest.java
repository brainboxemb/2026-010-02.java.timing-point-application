package oss.relay.timing.timingpoint.presentation.interfaces.api;

import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.defaultprofile.DefaultTimingDataCodec;

import java.time.Instant;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageWriterEventTimeTest {

    @Test
    public void timingDataEventUsesFixedMillisecondOccurredAtPrecision()
            throws Exception {

        Instant occurredAt =
                Instant.parse(
                        "2026-10-20T13:41:02.123456789Z");
        long recordTime =
                Instant.parse(
                                "2026-10-20T13:40:00.250Z")
                        .toEpochMilli();
        TimingRecord record =
                TimingRecord.createNodeOpened(
                        'A',
                        1L,
                        24,
                        recordTime,
                        recordTime);

        String json =
                MessageWriter.timingDataEvent(
                        occurredAt,
                        record,
                        new DefaultTimingDataCodec());

        assertTrue(
                json.contains(
                        "\"occurredAt\":\"2026-10-20T13:41:02.123Z\""));
        assertFalse(
                json.contains(
                        "123456789"));
    }
}
