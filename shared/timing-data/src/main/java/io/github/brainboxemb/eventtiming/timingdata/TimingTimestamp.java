package io.github.brainboxemb.eventtiming.timingdata;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Objects;

/**
 * Project-owned timing value.
 *
 * <p>A TimingTimestamp records both <em>what temporal information is known</em>
 * and <em>with what precision it is represented</em>. A value may therefore be
 * time-only, local date/time, or an absolute point on the UTC time line.
 * Missing date, zone or offset information is never invented by this class.</p>
 *
 * <p>Domain code that owns missing context performs the interpretation. For
 * example StageTiming may resolve a time-only stage start against an event
 * timezone and a registration date; UpstreamData itself can carry the
 * time-only value without requiring that context.</p>
 */
public final class TimingTimestamp implements Comparable<TimingTimestamp> {

    /**
     * Describes how complete the temporal information in this value is.
     */
    public enum Knowledge {
        /** Local time-of-day is known; calendar date is unknown. */
        TIME_ONLY,

        /** Local calendar date and time are known; zone/offset is unknown. */
        LOCAL_DATE_TIME,

        /** The value identifies one absolute instant on the UTC time line. */
        ABSOLUTE
    }

    /**
     * Fractional-second precision carried by a TimingTimestamp.
     *
     * <p>The generic timing model supports 0..9 decimal fractional digits. The
     * named constants cover the precisions currently used explicitly by SI-01;
     * other digit counts remain representable for compatible external data.</p>
     */
    public static final class Precision {
        public static final Precision SECOND = new Precision(0);
        public static final Precision CENTISECOND = new Precision(2);
        public static final Precision MILLISECOND = new Precision(3);
        public static final Precision NANOSECOND = new Precision(9);

        private final int fractionalDigits;

        private Precision(int fractionalDigits) {
            this.fractionalDigits = fractionalDigits;
        }

        public static Precision fractionalDigits(
                int fractionalDigits) {
            switch (fractionalDigits) {
                case 0:
                    return SECOND;
                case 2:
                    return CENTISECOND;
                case 3:
                    return MILLISECOND;
                case 9:
                    return NANOSECOND;
                default:
                    if (fractionalDigits < 0 || fractionalDigits > 9) {
                        throw new IllegalArgumentException(
                                "fractionalDigits must be in range 0..9");
                    }
                    return new Precision(fractionalDigits);
            }
        }

        public int fractionalDigits() {
            return fractionalDigits;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Precision
                    && fractionalDigits
                    == ((Precision) other).fractionalDigits;
        }

        @Override
        public int hashCode() {
            return fractionalDigits;
        }

        @Override
        public String toString() {
            return "fractionalDigits=" + fractionalDigits;
        }
    }

    private static final DateTimeFormatter ABSOLUTE_READER =
            reader("uuuu-MM-dd'T'HH:mm:ss", true);
    private static final DateTimeFormatter LOCAL_DATE_TIME_READER =
            reader("uuuu-MM-dd'T'HH:mm:ss", false);
    private static final DateTimeFormatter LOCAL_TIME_READER =
            reader("HH:mm:ss", false);

    private final Knowledge knowledge;
    private final Precision precision;
    private final LocalDate date;
    private final LocalTime time;
    private final Instant instant;

    /**
     * Creates an absolute timestamp and derives the minimum precision needed to
     * represent the Instant exactly.
     *
     * <p>Call the precision-aware overload when source precision is known
     * independently of the numeric value, for example a centisecond source that
     * happens to report {@code .00}.</p>
     */
    public TimingTimestamp(Instant instant) {
        this(
                requireInstant(instant),
                inferPrecision(
                        LocalDateTime.ofInstant(
                                instant,
                                ZoneOffset.UTC)
                                .toLocalTime()));
    }

    /**
     * Creates an absolute timestamp with explicit source precision.
     */
    public TimingTimestamp(
            Instant instant,
            Precision precision) {
        Instant requiredInstant =
                requireInstant(instant);
        LocalDateTime utc =
                LocalDateTime.ofInstant(
                        requiredInstant,
                        ZoneOffset.UTC);

        validatePrecision(
                utc.toLocalTime(),
                precision);

        this.knowledge = Knowledge.ABSOLUTE;
        this.precision = precision;
        this.date = utc.toLocalDate();
        this.time = utc.toLocalTime();
        this.instant = requiredInstant;
    }

    private TimingTimestamp(
            Knowledge knowledge,
            Precision precision,
            LocalDate date,
            LocalTime time,
            Instant instant) {
        if (knowledge == null) {
            throw new IllegalArgumentException(
                    "knowledge must not be null");
        }
        LocalTime requiredTime =
                requireTime(time);
        validatePrecision(
                requiredTime,
                precision);

        switch (knowledge) {
            case TIME_ONLY:
                if (date != null || instant != null) {
                    throw new IllegalArgumentException(
                            "TIME_ONLY must not contain date or instant");
                }
                break;
            case LOCAL_DATE_TIME:
                if (date == null || instant != null) {
                    throw new IllegalArgumentException(
                            "LOCAL_DATE_TIME requires date and no instant");
                }
                break;
            case ABSOLUTE:
                if (date == null || instant == null) {
                    throw new IllegalArgumentException(
                            "ABSOLUTE requires date and instant");
                }
                break;
            default:
                throw new IllegalStateException(
                        "Unsupported timestamp knowledge " + knowledge);
        }

        this.knowledge = knowledge;
        this.precision = precision;
        this.date = date;
        this.time = requiredTime;
        this.instant = instant;
    }

    /**
     * Creates a local civil time for which no calendar date is known.
     */
    public static TimingTimestamp localTime(
            LocalTime time) {
        LocalTime required =
                requireTime(time);
        return localTime(
                required,
                inferPrecision(required));
    }

    /**
     * Creates a local civil time with explicit source precision.
     */
    public static TimingTimestamp localTime(
            LocalTime time,
            Precision precision) {
        return new TimingTimestamp(
                Knowledge.TIME_ONLY,
                precision,
                null,
                time,
                null);
    }

    /**
     * Creates a local civil date/time for which no zone or offset is known.
     */
    public static TimingTimestamp localDateTime(
            LocalDate date,
            LocalTime time) {
        LocalTime required =
                requireTime(time);
        return localDateTime(
                date,
                required,
                inferPrecision(required));
    }

    /**
     * Creates a local civil date/time with explicit source precision.
     */
    public static TimingTimestamp localDateTime(
            LocalDate date,
            LocalTime time,
            Precision precision) {
        if (date == null) {
            throw new IllegalArgumentException(
                    "date must not be null");
        }
        return new TimingTimestamp(
                Knowledge.LOCAL_DATE_TIME,
                precision,
                date,
                time,
                null);
    }

    /**
     * Parses any canonical TimingTimestamp representation:
     * time-only, local date/time or absolute UTC.
     */
    public static TimingTimestamp parse(
            String text) {
        if (text == null) {
            throw new IllegalArgumentException(
                    "timestamp text must not be null");
        }

        Precision parsedPrecision =
                Precision.fractionalDigits(
                        fractionalDigits(text));

        try {
            if (text.endsWith("Z")) {
                LocalDateTime local =
                        LocalDateTime.parse(
                                text,
                                ABSOLUTE_READER);
                return new TimingTimestamp(
                        local.toInstant(
                                ZoneOffset.UTC),
                        parsedPrecision);
            }

            if (text.indexOf('T') >= 0) {
                LocalDateTime local =
                        LocalDateTime.parse(
                                text,
                                LOCAL_DATE_TIME_READER);
                return localDateTime(
                        local.toLocalDate(),
                        local.toLocalTime(),
                        parsedPrecision);
            }

            return localTime(
                    LocalTime.parse(
                            text,
                            LOCAL_TIME_READER),
                    parsedPrecision);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "timestamp must be time, local date/time, or canonical UTC "
                            + "with 0..9 fractional digits",
                    ex);
        }
    }

    /**
     * Parses a canonical absolute UTC timestamp and rejects incomplete values.
     */
    public static TimingTimestamp parseAbsolute(
            String text) {
        TimingTimestamp parsed =
                parse(text);
        if (!parsed.isAbsolute()) {
            throw new IllegalArgumentException(
                    "absolute timestamp requires date, time and Z");
        }
        return parsed;
    }

    public Knowledge knowledge() {
        return knowledge;
    }

    public Precision precision() {
        return precision;
    }

    public boolean hasDate() {
        return date != null;
    }

    public boolean isAbsolute() {
        return knowledge == Knowledge.ABSOLUTE;
    }

    /**
     * Returns the known local/canonical time component.
     *
     * <p>For an absolute value this is the UTC clock-time component.</p>
     */
    public LocalTime time() {
        return time;
    }

    /**
     * Returns the known date.
     *
     * @throws IllegalStateException when this value is time-only.
     */
    public LocalDate date() {
        if (date == null) {
            throw new IllegalStateException(
                    "timestamp has no known date");
        }
        return date;
    }

    /**
     * Returns the absolute instant.
     *
     * @throws IllegalStateException when date/zone knowledge is incomplete.
     */
    public Instant instant() {
        if (instant == null) {
            throw new IllegalStateException(
                    "timestamp is not absolute");
        }
        return instant;
    }

    /**
     * Formats the value while preserving its declared fractional precision.
     *
     * <p>This is appropriate for generic TimingTimestamp interchange. Existing
     * profile codecs may deliberately impose their own fixed formatting rules.</p>
     */
    public String toPrecisionString() {
        switch (knowledge) {
            case TIME_ONLY:
                return formatter(
                        "HH:mm:ss",
                        precision,
                        false)
                        .format(time);
            case LOCAL_DATE_TIME:
                return formatter(
                        "uuuu-MM-dd'T'HH:mm:ss",
                        precision,
                        false)
                        .format(
                                LocalDateTime.of(
                                        date,
                                        time));
            case ABSOLUTE:
                return formatter(
                        "uuuu-MM-dd'T'HH:mm:ss",
                        precision,
                        true)
                        .format(
                                LocalDateTime.ofInstant(
                                        instant,
                                        ZoneOffset.UTC));
            default:
                throw new IllegalStateException(
                        "Unsupported timestamp knowledge " + knowledge);
        }
    }

    @Override
    public int compareTo(TimingTimestamp other) {
        if (other == null) {
            throw new IllegalArgumentException(
                    "other must not be null");
        }
        if (!isAbsolute() || !other.isAbsolute()) {
            throw new IllegalStateException(
                    "only absolute TimingTimestamp values are directly comparable");
        }
        return instant.compareTo(
                other.instant);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TimingTimestamp)) {
            return false;
        }
        TimingTimestamp that =
                (TimingTimestamp) other;
        return knowledge == that.knowledge
                && precision.equals(that.precision)
                && Objects.equals(date, that.date)
                && time.equals(that.time)
                && Objects.equals(instant, that.instant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                knowledge,
                precision,
                date,
                time,
                instant);
    }

    /**
     * Keeps the historical compact text form. Use {@link #toPrecisionString()}
     * when representation precision itself is part of the interchange value.
     */
    @Override
    public String toString() {
        Precision compact =
                inferPrecision(time);
        switch (knowledge) {
            case TIME_ONLY:
                return formatter(
                        "HH:mm:ss",
                        compact,
                        false)
                        .format(time);
            case LOCAL_DATE_TIME:
                return formatter(
                        "uuuu-MM-dd'T'HH:mm:ss",
                        compact,
                        false)
                        .format(
                                LocalDateTime.of(
                                        date,
                                        time));
            case ABSOLUTE:
                return formatter(
                        "uuuu-MM-dd'T'HH:mm:ss",
                        compact,
                        true)
                        .format(
                                LocalDateTime.ofInstant(
                                        instant,
                                        ZoneOffset.UTC));
            default:
                throw new IllegalStateException(
                        "Unsupported timestamp knowledge " + knowledge);
        }
    }

    private static DateTimeFormatter reader(
            String basePattern,
            boolean zulu) {
        DateTimeFormatterBuilder builder =
                new DateTimeFormatterBuilder()
                        .parseCaseSensitive()
                        .appendPattern(basePattern)
                        .appendFraction(
                                ChronoField.NANO_OF_SECOND,
                                0,
                                9,
                                true);
        if (zulu) {
            builder.appendLiteral('Z');
        }
        return builder
                .toFormatter(Locale.ROOT)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    private static DateTimeFormatter formatter(
            String basePattern,
            Precision precision,
            boolean zulu) {
        if (precision == null) {
            throw new IllegalArgumentException(
                    "precision must not be null");
        }

        DateTimeFormatterBuilder builder =
                new DateTimeFormatterBuilder()
                        .appendPattern(basePattern);
        if (precision.fractionalDigits() > 0) {
            builder.appendFraction(
                    ChronoField.NANO_OF_SECOND,
                    precision.fractionalDigits(),
                    precision.fractionalDigits(),
                    true);
        }
        if (zulu) {
            builder.appendLiteral('Z');
        }
        return builder.toFormatter(
                Locale.ROOT);
    }

    private static Precision inferPrecision(
            LocalTime time) {
        int nanos =
                requireTime(time)
                        .getNano();
        if (nanos == 0) {
            return Precision.SECOND;
        }

        int fractionalDigits = 9;
        while (nanos % 10 == 0) {
            nanos /= 10;
            fractionalDigits--;
        }
        return Precision.fractionalDigits(
                fractionalDigits);
    }

    private static int fractionalDigits(
            String text) {
        int dot =
                text.indexOf('.');
        if (dot < 0) {
            return 0;
        }

        int end =
                text.endsWith("Z")
                ? text.length() - 1
                : text.length();
        int digits =
                end - dot - 1;
        if (digits < 1 || digits > 9) {
            throw new IllegalArgumentException(
                    "fractional timestamp precision must contain 1..9 digits");
        }
        return digits;
    }

    private static void validatePrecision(
            LocalTime time,
            Precision precision) {
        if (precision == null) {
            throw new IllegalArgumentException(
                    "precision must not be null");
        }

        int digits =
                precision.fractionalDigits();
        int nanos =
                requireTime(time)
                        .getNano();

        if (digits == 0) {
            if (nanos != 0) {
                throw new IllegalArgumentException(
                        "SECOND precision cannot represent fractional seconds");
            }
            return;
        }

        int quantum = 1;
        for (int i = digits; i < 9; i++) {
            quantum *= 10;
        }
        if (nanos % quantum != 0) {
            throw new IllegalArgumentException(
                    "timestamp value is finer than declared precision");
        }
    }

    private static Instant requireInstant(
            Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException(
                    "instant must not be null");
        }
        return instant;
    }

    private static LocalTime requireTime(
            LocalTime time) {
        if (time == null) {
            throw new IllegalArgumentException(
                    "time must not be null");
        }
        return time;
    }
}
