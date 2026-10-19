package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TagObservationTest {

    @Test
    public void retainsAbsoluteAndMonotonicObservationTimeAsPrimitiveLongs() {
        TimingTimestamp absolute =
                TimingTimestamp.parse("2026-10-18T12:30:15.251Z");
        long monotonicNanos = 123_456_789L;

        TagObservation observation =
                new TagObservation(
                        new TagId("N0042A"),
                        -42,
                        absolute.instant().toEpochMilli(),
                        monotonicNanos);

        assertEquals(
                absolute.instant().toEpochMilli(),
                observation.observedAtEpochMillis());
        assertEquals(
                monotonicNanos,
                observation.observedAtMonotonicNanos());
        assertEquals(
                absolute,
                observation.observedAt());
    }
}
