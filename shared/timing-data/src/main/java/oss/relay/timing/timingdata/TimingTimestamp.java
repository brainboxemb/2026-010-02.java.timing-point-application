package oss.relay.timing.timingdata;

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
 * Project-owned timing value with explicit knowledge about what part of a
 * timestamp is known.
 *
 * <p>A TimingTimestamp may contain only a local civil time, a local date and
 * time, or an absolute point on the UTC time line. Missing date/zone knowledge
 * is never invented by this value object. Domain logic that owns that context
 * performs the interpretation explicitly.</p>
 *
 * <p>This keeps one timing type across protocols and domain state. An upstream
 * protocol may therefore supply time-of-day only without being forced to invent
 * a date, while TimingData can continue to use absolute timestamps.</p>
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

    private static final DateTimeFormatter ABSOLUTE_FORMATTER =
            new DateTimeFormatterBuilder()
                    .parseCaseSensitive()
                    .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .appendFraction(
                            ChronoField.NANO_OF_SECOND,
                            0,
                            9,
                            true)
                    .appendLiteral('Z')
                    .toFormatter(Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter LOCAL_DATE_TIME_FORMATTER =
            new DateTimeFormatterBuilder()
                    .parseCaseSensitive()
                    .appendPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .appendFraction(
                            ChronoField.NANO_OF_SECOND,
                            0,
                            9,
                            true)
                    .toFormatter(Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter LOCAL_TIME_FORMATTER =
            new DateTimeFormatterBuilder()
                    .parseCaseSensitive()
                    .appendPattern("HH:mm:ss")
                    .appendFraction(
                            ChronoField.NANO_OF_SECOND,
                            0,
                            9,
                            true)
                    .toFormatter(Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private final Knowledge knowledge;
    private final LocalDate date;
    private final LocalTime time;
    private final Instant instant;

    /**
     * Creates an absolute timestamp.
     *
     * <p>This constructor is retained for existing TimingData callers. Code that
     * has only local civil time must use {@link #localTime(LocalTime)} or
     * {@link #localDateTime(LocalDate, LocalTime)} rather than inventing an
     * Instant.</p>
     */
    public TimingTimestamp(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException(
                    "instant must not be null");
        }

        LocalDateTime utc =
                LocalDateTime.ofInstant(
                        instant,
                        ZoneOffset.UTC);

        this.knowledge = Knowledge.ABSOLUTE;
        this.date = utc.toLocalDate();
        this.time = utc.toLocalTime();
        this.instant = instant;
    }

    private TimingTimestamp(
            Knowledge knowledge,
            LocalDate date,
            LocalTime time,
            Instant instant) {
        if (knowledge == null) {
            throw new IllegalArgumentException(
                    "knowledge must not be null");
        }
        if (time == null) {
            throw new IllegalArgumentException(
                    "time must not be null");
        }

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
        this.date = date;
        this.time = time;
        this.instant = instant;
    }

    /** Creates a local civil time for which no calendar date is known. */
    public static TimingTimestamp localTime(
            LocalTime time) {
        return new TimingTimestamp(
                Knowledge.TIME_ONLY,
                null,
                requireTime(time),
                null);
    }

    /** Creates a local civil date/time for which no zone or offset is known. */
    public static TimingTimestamp localDateTime(
            LocalDate date,
            LocalTime time) {
        if (date == null) {
            throw new IllegalArgumentException(
                    "date must not be null");
        }
        return new TimingTimestamp(
                Knowledge.LOCAL_DATE_TIME,
                date,
                requireTime(time),
                null);
    }

    /**
     * Parses time-only, local date/time or the canonical absolute UTC form.
     */
    public static TimingTimestamp parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException(
                    "timestamp text must not be null");
        }

        try {
            if (text.endsWith("Z")) {
                LocalDateTime local =
                        LocalDateTime.parse(
                                text,
                                ABSOLUTE_FORMATTER);
                return new TimingTimestamp(
                        local.toInstant(
                                ZoneOffset.UTC));
            }

            if (text.indexOf('T') >= 0) {
                LocalDateTime local =
                        LocalDateTime.parse(
                                text,
                                LOCAL_DATE_TIME_FORMATTER);
                return localDateTime(
                        local.toLocalDate(),
                        local.toLocalTime());
            }

            return localTime(
                    LocalTime.parse(
                            text,
                            LOCAL_TIME_FORMATTER));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "timestamp must be time, local date/time, or canonical UTC "
                            + "with 0..9 fractional digits",
                    ex);
        }
    }

    /**
     * Parses an absolute UTC timestamp and rejects incomplete timestamp forms.
     */
    public static TimingTimestamp parseAbsolute(String text) {
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

    public boolean hasDate() {
        return date != null;
    }

    public boolean isAbsolute() {
        return knowledge == Knowledge.ABSOLUTE;
    }

    /**
     * Returns the known clock-time component.
     *
     * <p>For an absolute timestamp this is the UTC clock-time component.</p>
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
                && Objects.equals(date, that.date)
                && time.equals(that.time)
                && Objects.equals(instant, that.instant);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                knowledge,
                date,
                time,
                instant);
    }

    @Override
    public String toString() {
        switch (knowledge) {
            case TIME_ONLY:
                return LOCAL_TIME_FORMATTER.format(
                        time);
            case LOCAL_DATE_TIME:
                return LOCAL_DATE_TIME_FORMATTER.format(
                        LocalDateTime.of(
                                date,
                                time));
            case ABSOLUTE:
                return ABSOLUTE_FORMATTER.format(
                        LocalDateTime.ofInstant(
                                instant,
                                ZoneOffset.UTC));
            default:
                throw new IllegalStateException(
                        "Unsupported timestamp knowledge " + knowledge);
        }
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
