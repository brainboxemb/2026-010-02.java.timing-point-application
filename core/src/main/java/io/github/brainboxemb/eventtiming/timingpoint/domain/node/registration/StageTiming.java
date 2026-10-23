package io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Converts and compares compact UTC stage-start values.
 *
 * <p>Time-only values retain only UTC HHmmsscc and stay below 100000000. Values with a date
 * retain UTC yyyyMMddHHmmsscc. No missing date is added while converting a time-only value.</p>
 */
public final class StageTiming {
    static final long NO_ELAPSED_MILLIS = Long.MIN_VALUE;

    private static final long DATE_THRESHOLD = 100_000_000L;
    private static final long CENTISECOND_NANOS = 10_000_000L;
    private static final long DAY_MILLIS = 86_400_000L;
    private static final long HALF_DAY_MILLIS = 43_200_000L;

    private StageTiming() {
    }

    /**
     * Converts one protocol stage start to the compact UTC representation.
     *
     * <p>Absolute values already define their UTC instant. Local date/time values use the
     * configured source zone and reject DST gaps/overlaps. Time-only values use only the
     * zone offset active at {@code offsetReference}; the stored result remains time-only.</p>
     */
    public static long compactUtc(
            TimingTimestamp start,
            ZoneId sourceZone,
            Instant offsetReference) {

        failIfInvalidArgument(start != null, "stage start must not be null");
        failIfInvalidArgument(sourceZone != null, "sourceZone must not be null");
        failIfInvalidArgument(offsetReference != null, "offsetReference must not be null");

        if (start.isAbsolute()) {
            return compactUtc(start.instant());
        }
        if (start.hasDate()) {
            return compactUtc(
                    localInstant(
                            LocalDateTime.of(start.date(), start.time()),
                            sourceZone));
        }

        ZoneOffset offset = timeOnlyOffset(sourceZone, offsetReference);
        long localMillis = timeOfDayMillis(start.time());
        long utcMillis = Math.floorMod(
                localMillis - offset.getTotalSeconds() * 1000L,
                DAY_MILLIS);
        return compactTime(LocalTime.ofNanoOfDay(utcMillis * 1_000_000L));
    }

    /** Converts one absolute instant to compact UTC yyyyMMddHHmmsscc. */
    public static long compactUtc(Instant instant) {

        failIfInvalidArgument(instant != null, "instant must not be null");
        return compactDateTime(
                LocalDateTime.ofInstant(
                        instant,
                        ZoneOffset.UTC));
    }

    /** Reconstructs the public timestamp knowledge represented by one compact UTC value. */
    public static TimingTimestamp timestamp(long compactUtc) {

        if (hasDate(compactUtc)) {
            return new TimingTimestamp(
                    dateTime(compactUtc)
                            .toInstant(ZoneOffset.UTC));
        }
        return TimingTimestamp.localTime(time(compactUtc));
    }

    /**
     * Returns elapsed milliseconds, or NO_ELAPSED_MILLIS when a time-only start is ambiguous.
     *
     * <p>A dated start is compared directly. A time-only start is interpreted as the same UTC
     * day or previous UTC day and is accepted only when the resulting elapsed time is strictly
     * less than twelve hours. This makes midnight rollover deterministic without inventing a date.</p>
     */
    static long elapsedMillisOrMissing(
            long compactUtc,
            long passageEpochMillis) {

        if (hasDate(compactUtc)) {
            long elapsed =
                    passageEpochMillis
                            - dateTime(compactUtc)
                                    .toInstant(ZoneOffset.UTC)
                                    .toEpochMilli();
            return elapsed >= 0L
                    ? elapsed
                    : NO_ELAPSED_MILLIS;
        }

        long passageMillisOfDay = Math.floorMod(passageEpochMillis, DAY_MILLIS);
        long startMillisOfDay = timeOfDayMillis(time(compactUtc));
        long elapsed = passageMillisOfDay - startMillisOfDay;
        if (elapsed < 0L) {
            elapsed += DAY_MILLIS;
        }
        return elapsed < HALF_DAY_MILLIS
                ? elapsed
                : NO_ELAPSED_MILLIS;
    }

    /** Reports whether one compact stage start contains an explicit UTC date. */
    public static boolean hasDate(long compactUtc) {

        failIfInvalidArgument(compactUtc >= 0L, "compactUtc must not be negative");
        return compactUtc >= DATE_THRESHOLD;
    }

    private static Instant localInstant(
            LocalDateTime localDateTime,
            ZoneId zone) {

        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(localDateTime);
        failIfInvalidArgument(
                offsets.size() == 1,
                "local stage start is ambiguous or nonexistent in zone %s",
                zone);
        return localDateTime.toInstant(offsets.get(0));
    }

    /**
     * Resolves only the UTC offset for time-only input.
     *
     * <p>A variable-offset zone is rejected when a transition lies within twelve hours of the
     * timing reference, because time-only data cannot identify which side of that transition
     * supplied the wall-clock value.</p>
     */
    private static ZoneOffset timeOnlyOffset(
            ZoneId zone,
            Instant offsetReference) {

        ZoneRules rules = zone.getRules();
        if (!rules.isFixedOffset()) {
            Instant lower = offsetReference.minusMillis(HALF_DAY_MILLIS);
            Instant upper = offsetReference.plusMillis(HALF_DAY_MILLIS);
            ZoneOffsetTransition previous =
                    rules.previousTransition(
                            offsetReference.plusNanos(1L));
            ZoneOffsetTransition next =
                    rules.nextTransition(
                            offsetReference.minusNanos(1L));
            failIfInvalidArgument(
                    previous == null || previous.getInstant().isBefore(lower),
                    "time-only stage start is ambiguous near a zone transition");
            failIfInvalidArgument(
                    next == null || next.getInstant().isAfter(upper),
                    "time-only stage start is ambiguous near a zone transition");
        }
        return rules.getOffset(offsetReference);
    }

    private static long compactDateTime(LocalDateTime value) {

        return value.getYear() * 1_000_000_000_000L
                + value.getMonthValue() * 10_000_000_000L
                + value.getDayOfMonth() * 100_000_000L
                + compactTime(value.toLocalTime());
    }

    private static long compactTime(LocalTime value) {

        return value.getHour() * 1_000_000L
                + value.getMinute() * 10_000L
                + value.getSecond() * 100L
                + value.getNano() / CENTISECOND_NANOS;
    }

    private static LocalDateTime dateTime(long compactUtc) {

        failIfInvalidArgument(hasDate(compactUtc), "compactUtc has no date");
        long remainder = compactUtc;
        int year = (int) (remainder / 1_000_000_000_000L);
        remainder %= 1_000_000_000_000L;
        int month = (int) (remainder / 10_000_000_000L);
        remainder %= 10_000_000_000L;
        int day = (int) (remainder / 100_000_000L);
        remainder %= 100_000_000L;

        try {
            return LocalDateTime.of(
                    LocalDate.of(year, month, day),
                    time(remainder));
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException(
                    "invalid compact UTC date/time " + compactUtc,
                    ex);
        }
    }

    private static LocalTime time(long compactUtc) {

        failIfInvalidArgument(compactUtc >= 0L, "compactUtc must not be negative");
        failIfInvalidArgument(compactUtc < DATE_THRESHOLD, "compactUtc time part is too large");

        int hour = (int) (compactUtc / 1_000_000L);
        long remainder = compactUtc % 1_000_000L;
        int minute = (int) (remainder / 10_000L);
        remainder %= 10_000L;
        int second = (int) (remainder / 100L);
        int centisecond = (int) (remainder % 100L);

        try {
            return LocalTime.of(
                    hour,
                    minute,
                    second,
                    centisecond * (int) CENTISECOND_NANOS);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException(
                    "invalid compact UTC time " + compactUtc,
                    ex);
        }
    }

    private static long timeOfDayMillis(LocalTime value) {

        return value.toNanoOfDay() / 1_000_000L;
    }
}
