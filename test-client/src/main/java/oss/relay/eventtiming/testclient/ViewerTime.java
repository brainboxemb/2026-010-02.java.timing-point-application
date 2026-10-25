package oss.relay.eventtiming.testclient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;

/** Presentation-only conversion of absolute protocol timestamps to operator-local time. */
final class ViewerTime {
    private static final DateTimeFormatter TIME =
            new DateTimeFormatterBuilder()
                    .appendPattern("HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 3, true)
                    .toFormatter(Locale.ROOT);
    private static final DateTimeFormatter DATE_TIME =
            new DateTimeFormatterBuilder()
                    .appendPattern("uuuu-MM-dd HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 3, true)
                    .toFormatter(Locale.ROOT);
    private static final DateTimeFormatter EFFECTIVE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SS", Locale.ROOT);
    private static final DateTimeFormatter RECORDED =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.ROOT);

    private ViewerTime() {
    }

    static ZoneId localZone() {
        return ZoneId.systemDefault();
    }

    static String instant(Instant instant, boolean includeDate) {
        return instant(instant, includeDate, localZone());
    }

    static String instant(
            Instant instant,
            boolean includeDate,
            ZoneId zone) {
        if (instant == null) {
            return "—";
        }
        if (zone == null) {
            throw new IllegalArgumentException("zone must not be null");
        }
        return (includeDate ? DATE_TIME : TIME)
                .withZone(zone)
                .format(instant);
    }

    static String effective(String utcText) {
        return protocol(utcText, localZone(), EFFECTIVE);
    }

    static String recorded(String utcText) {
        return protocol(utcText, localZone(), RECORDED);
    }

    static String effective(String utcText, ZoneId zone) {
        return protocol(utcText, zone, EFFECTIVE);
    }

    static String recorded(String utcText, ZoneId zone) {
        return protocol(utcText, zone, RECORDED);
    }

    private static String protocol(
            String utcText,
            ZoneId zone,
            DateTimeFormatter formatter) {
        if (utcText == null || utcText.isBlank()) {
            return utcText == null ? "" : utcText;
        }
        if (zone == null) {
            throw new IllegalArgumentException("zone must not be null");
        }
        try {
            return formatter
                    .withZone(zone)
                    .format(Instant.parse(utcText));
        } catch (DateTimeParseException ex) {
            return utcText;
        }
    }
}
