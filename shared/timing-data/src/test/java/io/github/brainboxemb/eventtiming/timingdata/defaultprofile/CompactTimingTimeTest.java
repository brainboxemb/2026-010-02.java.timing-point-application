package io.github.brainboxemb.eventtiming.timingdata.defaultprofile;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CompactTimingTimeTest {
    @Test
    public void roundTripsAnAbsoluteUtcTimestamp() {
        TimingTimestamp timestamp = TimingTimestamp.parse("2026-10-18T12:30:15.25Z");

        long packed = CompactTimingTime.fromTimestamp(timestamp);

        assertEquals(2026101812301525L, packed);
        assertTrue(CompactTimingTime.hasDate(packed));
        assertEquals(timestamp, CompactTimingTime.toTimestamp(packed));
    }

    @Test
    public void storesTimeWithoutInventingDate() {
        long packed = CompactTimingTime.timeOfDay(12, 30, 15, 25);

        assertEquals(12301525L, packed);
        assertFalse(CompactTimingTime.hasDate(packed));
    }

    @Test
    public void roundTripsMidnightAndLeapDay() {
        TimingTimestamp timestamp = TimingTimestamp.parse("2024-02-29T00:00:00.00Z");
        assertEquals(timestamp, CompactTimingTime.toTimestamp(
                CompactTimingTime.fromTimestamp(timestamp)));
    }

    @Test
    public void truncatesSubCentisecondTime() {
        long packed = CompactTimingTime.fromTimestamp(
                TimingTimestamp.parse("2026-10-18T12:30:15.251Z"));

        assertEquals(2026101812301525L, packed);
        assertEquals(
                TimingTimestamp.parse("2026-10-18T12:30:15.25Z"),
                CompactTimingTime.toTimestamp(packed));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidClockTime() {
        CompactTimingTime.timeOfDay(24, 0, 0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidDate() {
        CompactTimingTime.toTimestamp(2026023012301525L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTimeOnlyAsAbsolute() {
        CompactTimingTime.toTimestamp(12301525L);
    }
}
