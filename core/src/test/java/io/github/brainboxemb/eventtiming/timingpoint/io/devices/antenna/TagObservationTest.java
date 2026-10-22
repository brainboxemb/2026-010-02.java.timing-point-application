package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.eventdata.TagId;

import java.time.Instant;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TagObservationTest {

    @Test
    public void retainsAbsoluteAndMonotonicObservationTimeAsPrimitiveLongs() {
        Instant absolute =
                Instant.parse("2026-10-18T12:30:15.251Z");
        long monotonicNanos = 123_456_789L;

        TagObservation observation =
                new TagObservation(
                        new TagId("N0042A"),
                        -42,
                        absolute.toEpochMilli(),
                        monotonicNanos);

        assertEquals(
                absolute.toEpochMilli(),
                observation.observedAtEpochMillis());
        assertEquals(
                monotonicNanos,
                observation.observedAtMonotonicNanos());
    }
}
