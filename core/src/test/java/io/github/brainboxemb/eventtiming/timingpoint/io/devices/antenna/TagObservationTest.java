package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TagObservationTest {

    @Test
    public void retainsObservationTimeAsCompactCentisecondValue() {
        TagObservation observation =
                new TagObservation(
                        new TagId("N0042A"),
                        -42,
                        TimingTimestamp.parse(
                                "2026-10-18T12:30:15.251Z"));

        assertEquals(
                2026101812301525L,
                observation.observedAtValue());
        assertEquals(
                TimingTimestamp.parse(
                        "2026-10-18T12:30:15.25Z"),
                observation.observedAt());
    }
}
