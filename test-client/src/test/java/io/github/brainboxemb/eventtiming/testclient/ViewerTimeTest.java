package io.github.brainboxemb.eventtiming.testclient;

import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ViewerTimeTest {
    private static final ZoneId AMSTERDAM =
            ZoneId.of("Europe/Amsterdam");

    @Test
    void convertsAbsoluteUtcInstantToLocalViewerTime() {
        Instant instant =
                Instant.parse("2026-10-24T19:30:00.123Z");

        assertEquals(
                "21:30:00.123",
                ViewerTime.instant(
                        instant,
                        false,
                        AMSTERDAM));
        assertEquals(
                "2026-10-24 21:30:00.123",
                ViewerTime.instant(
                        instant,
                        true,
                        AMSTERDAM));
    }

    @Test
    void convertsAcrossLocalCalendarBoundary() {
        assertEquals(
                "2026-10-25 01:30:00",
                ViewerTime.instant(
                        Instant.parse("2026-10-24T23:30:00Z"),
                        true,
                        AMSTERDAM));
    }

    @Test
    void preservesIf05FieldPrecisionInLocalLogBookPresentation() {
        assertEquals(
                "2026-10-24 21:30:00.12",
                ViewerTime.effective(
                        "2026-10-24T19:30:00.12Z",
                        AMSTERDAM));
        assertEquals(
                "2026-10-24 21:30:00.123",
                ViewerTime.recorded(
                        "2026-10-24T19:30:00.123Z",
                        AMSTERDAM));
    }

    @Test
    void leavesUnparseableProtocolTextVisibleForDiagnosis() {
        assertEquals(
                "not-a-time",
                ViewerTime.effective(
                        "not-a-time",
                        AMSTERDAM));
    }
}
