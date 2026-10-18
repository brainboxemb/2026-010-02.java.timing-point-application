package io.github.brainboxemb.eventtiming.timingdata;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Compact UTC timing value at centisecond precision.
 *
 * <p>A date and time are stored as yyyyMMddHHmmsscc; a time without a
 * date is HHmmsscc. Values below 100000000 have no date. Absolute input
 * is truncated to centisecond precision before it is stored.</p>
 */
public final class CompactTimingTime {
    private static final long DATE_THRESHOLD = 100_000_000L;
    private static final long TIME_FACTOR = 100_000_000L;
    private static final long CENTISECONDS_PER_DAY = 8_640_000L;
    private static final int[] DAYS_BEFORE_MONTH =
            {0, 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
    private static final int[] DAYS_IN_MONTH =
            {0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    private CompactTimingTime() {
    }

    /** Packs an absolute timestamp at centisecond precision. */
    public static long fromTimestamp(TimingTimestamp timestamp) {
        if (timestamp == null || !timestamp.isAbsolute()) {
            throw new IllegalArgumentException("timestamp must be absolute");
        }
        Instant instant = timestamp.instant();
        LocalDateTime utc = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        int year = utc.getYear();
        if (year < 1 || year > 9999) {
            throw new IllegalArgumentException("year must be in range 1..9999");
        }
        long date = year * 10000L + utc.getMonthValue() * 100L + utc.getDayOfMonth();
        return date * TIME_FACTOR + timeOfDay(
                utc.getHour(),
                utc.getMinute(),
                utc.getSecond(),
                utc.getNano() / 10_000_000);
    }

    /** Packs one local clock time without inventing a calendar date. */
    public static long timeOfDay(int hour, int minute, int second, int centisecond) {
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59
                || second < 0 || second > 59 || centisecond < 0 || centisecond > 99) {
            throw new IllegalArgumentException("invalid time-of-day");
        }
        return hour * 1_000_000L + minute * 10_000L + second * 100L + centisecond;
    }

    /** Reports whether the compact value contains a calendar date. */
    public static boolean hasDate(long value) {
        return value >= DATE_THRESHOLD;
    }

    /** Returns later minus earlier in centiseconds without creating timestamp objects. */
    public static long elapsedCentiseconds(
            long earlier,
            long later) {
        return absoluteCentiseconds(later)
                - absoluteCentiseconds(earlier);
    }

    /** Restores an absolute UTC timestamp from its compact value. */
    public static TimingTimestamp toTimestamp(long value) {
        if (!hasDate(value)) {
            throw new IllegalArgumentException("absolute timestamp requires a date");
        }
        long date = value / TIME_FACTOR;
        long time = value % TIME_FACTOR;
        int year = (int) (date / 10000);
        int month = (int) (date / 100 % 100);
        int day = (int) (date % 100);
        int hour = (int) (time / 1_000_000);
        int minute = (int) (time / 10_000 % 100);
        int second = (int) (time / 100 % 100);
        int centisecond = (int) (time % 100);
        try {
            LocalDateTime utc = LocalDateTime.of(
                    year,
                    month,
                    day,
                    hour,
                    minute,
                    second,
                    centisecond * 10_000_000);
            return new TimingTimestamp(utc.toInstant(ZoneOffset.UTC));
        } catch (java.time.DateTimeException ex) {
            throw new IllegalArgumentException("invalid compact timestamp", ex);
        }
    }

    /** Converts one compact absolute value to a continuous centisecond count. */
    private static long absoluteCentiseconds(long value) {
        if (!hasDate(value)) {
            throw new IllegalArgumentException("absolute timestamp requires a date");
        }

        long date = value / TIME_FACTOR;
        long time = value % TIME_FACTOR;
        int year = (int) (date / 10000);
        int month = (int) (date / 100 % 100);
        int day = (int) (date % 100);
        int hour = (int) (time / 1_000_000);
        int minute = (int) (time / 10_000 % 100);
        int second = (int) (time / 100 % 100);
        int centisecond = (int) (time % 100);

        validateDateTime(
                year,
                month,
                day,
                hour,
                minute,
                second,
                centisecond);

        long previousYears = year - 1L;
        long days =
                previousYears * 365L
                        + previousYears / 4L
                        - previousYears / 100L
                        + previousYears / 400L
                        + DAYS_BEFORE_MONTH[month]
                        + (month > 2 && leapYear(year) ? 1L : 0L)
                        + day - 1L;

        long timeCentiseconds =
                hour * 360_000L
                        + minute * 6_000L
                        + second * 100L
                        + centisecond;
        return days * CENTISECONDS_PER_DAY
                + timeCentiseconds;
    }

    /** Validates the fields decoded from one compact absolute timestamp. */
    private static void validateDateTime(
            int year,
            int month,
            int day,
            int hour,
            int minute,
            int second,
            int centisecond) {
        if (year < 1 || year > 9999
                || month < 1 || month > 12
                || hour < 0 || hour > 23
                || minute < 0 || minute > 59
                || second < 0 || second > 59
                || centisecond < 0 || centisecond > 99) {
            throw new IllegalArgumentException("invalid compact timestamp");
        }

        int maxDay =
                DAYS_IN_MONTH[month]
                        + (month == 2 && leapYear(year) ? 1 : 0);
        if (day < 1 || day > maxDay) {
            throw new IllegalArgumentException("invalid compact timestamp");
        }
    }

    /** Reports Gregorian leap years without creating date objects. */
    private static boolean leapYear(int year) {
        return year % 4 == 0
                && (year % 100 != 0
                        || year % 400 == 0);
    }
}
