package oss.relay.timing.timingdata;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimingTimestampTest {

    @Test
    public void parsesAndWritesCompactUtcText() {
        TimingTimestamp timestamp =
                TimingTimestamp.parse(
                        "2026-09-30T20:01:39.123000000Z");

        assertEquals(
                TimingTimestamp.Knowledge.ABSOLUTE,
                timestamp.knowledge());
        assertTrue(timestamp.hasDate());
        assertTrue(timestamp.isAbsolute());
        assertEquals(
                Instant.parse(
                        "2026-09-30T20:01:39.123Z"),
                timestamp.instant());
        assertEquals(
                "2026-09-30T20:01:39.123Z",
                timestamp.toString());
    }

    @Test
    public void timeOnlyKeepsUnknownDateExplicit() {
        TimingTimestamp timestamp =
                TimingTimestamp.localTime(
                        LocalTime.of(
                                23,
                                59,
                                50));

        assertEquals(
                TimingTimestamp.Knowledge.TIME_ONLY,
                timestamp.knowledge());
        assertFalse(timestamp.hasDate());
        assertFalse(timestamp.isAbsolute());
        assertEquals(
                LocalTime.of(
                        23,
                        59,
                        50),
                timestamp.time());
        assertEquals(
                "23:59:50",
                timestamp.toString());
    }

    @Test
    public void localDateTimeKeepsZoneUnknown() {
        TimingTimestamp timestamp =
                TimingTimestamp.localDateTime(
                        LocalDate.of(
                                2026,
                                10,
                                13),
                        LocalTime.of(
                                23,
                                59,
                                50));

        assertEquals(
                TimingTimestamp.Knowledge.LOCAL_DATE_TIME,
                timestamp.knowledge());
        assertTrue(timestamp.hasDate());
        assertFalse(timestamp.isAbsolute());
        assertEquals(
                LocalDate.of(
                        2026,
                        10,
                        13),
                timestamp.date());
        assertEquals(
                "2026-10-13T23:59:50",
                timestamp.toString());
    }

    @Test(expected = IllegalStateException.class)
    public void timeOnlyDoesNotInventDate() {
        TimingTimestamp.localTime(
                LocalTime.NOON)
                .date();
    }

    @Test(expected = IllegalStateException.class)
    public void localDateTimeDoesNotInventAbsoluteInstant() {
        TimingTimestamp.localDateTime(
                LocalDate.of(
                        2026,
                        10,
                        13),
                LocalTime.NOON)
                .instant();
    }

    @Test
    public void writesWholeSecondWithoutFraction() {
        TimingTimestamp timestamp =
                new TimingTimestamp(
                        Instant.parse(
                                "2026-09-30T20:01:39Z"));

        assertEquals(
                "2026-09-30T20:01:39Z",
                timestamp.toString());
    }

    @Test
    public void preservesRequiredFractionPrecision() {
        TimingTimestamp timestamp =
                TimingTimestamp.parse(
                        "2026-09-30T20:01:39.123456789Z");

        assertEquals(
                "2026-09-30T20:01:39.123456789Z",
                timestamp.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOffsetInsteadOfCanonicalZuluTime() {
        TimingTimestamp.parse(
                "2026-09-30T22:01:39.123+02:00");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMoreThanNanosecondPrecision() {
        TimingTimestamp.parse(
                "2026-09-30T20:01:39.1234567891Z");
    }

    @Test
    public void comparesByAbsoluteInstant() {
        TimingTimestamp first =
                TimingTimestamp.parse(
                        "2026-09-30T20:01:39.123Z");
        TimingTimestamp second =
                TimingTimestamp.parse(
                        "2026-09-30T20:01:39.124Z");

        assertTrue(
                first.compareTo(second) < 0);
    }

    @Test(expected = IllegalStateException.class)
    public void localValuesAreNotComparedAsAbsoluteInstants() {
        TimingTimestamp.localTime(
                LocalTime.of(
                        12,
                        0))
                .compareTo(
                        TimingTimestamp.localTime(
                                LocalTime.of(
                                        13,
                                        0)));
    }
}
