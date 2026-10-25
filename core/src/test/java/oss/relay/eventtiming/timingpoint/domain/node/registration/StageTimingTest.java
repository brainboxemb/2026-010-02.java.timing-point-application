package oss.relay.eventtiming.timingpoint.domain.node.registration;

import oss.relay.eventtiming.timingdata.TimingTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class StageTimingTest {

    @Test
    public void timeOnlyConversionPreservesMissingDateAcrossUtcDay() {

        assertEquals(
                23_00_00_00L,
                StageTiming.compactUtc(
                        TimingTimestamp.localTime(LocalTime.of(1, 0)),
                        ZoneOffset.ofHours(2),
                        Instant.parse("2026-10-23T12:00:00Z")));
    }

    @Test
    public void datedLocalConversionCarriesUtcDateAcrossPreviousDay() {

        assertEquals(
                2026_10_22_23_00_00_00L,
                StageTiming.compactUtc(
                        TimingTimestamp.localDateTime(
                                LocalDate.of(2026, 10, 23),
                                LocalTime.of(1, 0)),
                        ZoneOffset.ofHours(2),
                        Instant.parse("2026-10-23T12:00:00Z")));
    }

    @Test
    public void absoluteConversionKeepsUtcDateAndTruncatesToCentiseconds() {

        assertEquals(
                2026_10_23_12_34_56_78L,
                StageTiming.compactUtc(
                        TimingTimestamp.parse(
                                "2026-10-23T12:34:56.789Z"),
                        ZoneOffset.UTC,
                        Instant.EPOCH));
    }

    @Test
    public void localDateTimeRejectsDstGapAndOverlap() {

        assertRejected(
                TimingTimestamp.localDateTime(
                        LocalDate.of(2026, 3, 29),
                        LocalTime.of(2, 30)),
                ZoneId.of("Europe/Amsterdam"),
                Instant.parse("2026-03-29T00:00:00Z"));
        assertRejected(
                TimingTimestamp.localDateTime(
                        LocalDate.of(2026, 10, 25),
                        LocalTime.of(2, 30)),
                ZoneId.of("Europe/Amsterdam"),
                Instant.parse("2026-10-25T00:00:00Z"));
    }

    @Test
    public void timeOnlyRejectsVariableOffsetZoneNearTransition() {

        assertRejected(
                TimingTimestamp.localTime(LocalTime.of(1, 30)),
                ZoneId.of("Europe/Amsterdam"),
                Instant.parse("2026-10-25T00:30:00Z"));
    }

    @Test
    public void timeOnlyElapsedCrossesMidnightWithoutAddingDate() {

        long passage = Instant.parse("2026-10-24T00:30:00Z").toEpochMilli();

        assertEquals(
                40L * 60L * 1000L,
                StageTiming.elapsedMillisOrMissing(
                        23_50_00_00L,
                        passage));
    }

    @Test
    public void timeOnlyElapsedRejectsExactlyTwelveHoursAndLonger() {

        long passage = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli();

        assertEquals(
                StageTiming.NO_ELAPSED_MILLIS,
                StageTiming.elapsedMillisOrMissing(
                        00_00_00_00L,
                        passage));
        assertEquals(
                StageTiming.NO_ELAPSED_MILLIS,
                StageTiming.elapsedMillisOrMissing(
                        23_00_00_00L,
                        passage));
    }

    @Test
    public void everyMinutePreservesKnownElapsedBelowTwelveHoursAcrossRollover() {

        long[] elapsedValues = {
            0L,
            10L,
            1000L,
            60_000L,
            3_600_000L,
            43_199_990L
        };

        long dayStart = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli();
        for (int minute = 0; minute < 1440; minute++) {
            long passage = dayStart + minute * 60_000L;
            for (long expected : elapsedValues) {
                long start = passage - expected;
                long compactStart =
                        compactTime(
                                Instant.ofEpochMilli(start)
                                        .atOffset(ZoneOffset.UTC)
                                        .toLocalTime());
                assertEquals(
                        "minute=" + minute + ", elapsed=" + expected,
                        expected,
                        StageTiming.elapsedMillisOrMissing(
                                compactStart,
                                passage));
            }
        }
    }

    @Test
    public void datedElapsedUsesExplicitDateAcrossYearBoundary() {

        long start = 2025_12_31_23_59_50_00L;
        long passage = Instant.parse("2026-01-01T00:00:10Z").toEpochMilli();

        assertEquals(
                20_000L,
                StageTiming.elapsedMillisOrMissing(
                        start,
                        passage));
    }

    private static long compactTime(LocalTime time) {

        return time.getHour() * 1_000_000L
                + time.getMinute() * 10_000L
                + time.getSecond() * 100L
                + time.getNano() / 10_000_000L;
    }

    private static void assertRejected(
            TimingTimestamp timestamp,
            ZoneId zone,
            Instant reference) {

        try {
            StageTiming.compactUtc(timestamp, zone, reference);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
