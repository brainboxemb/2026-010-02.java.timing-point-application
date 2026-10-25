package oss.relay.eventtiming.timingpoint.platform.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ClockTimeSourceTest {

    @Test
    public void readsAbsoluteTimeFromInjectedClock() {
        ZoneId zone = ZoneId.of("Europe/Amsterdam");
        Clock clock =
                Clock.fixed(
                        Instant.parse(
                                "2026-10-07T06:00:00Z"),
                        zone);

        TimeSource timeSource =
                new ClockTimeSource(
                        clock);

        assertEquals(
                Instant.parse(
                        "2026-10-07T06:00:00Z"),
                timeSource.now());
        assertEquals(
                zone,
                timeSource.zone());
    }
}
