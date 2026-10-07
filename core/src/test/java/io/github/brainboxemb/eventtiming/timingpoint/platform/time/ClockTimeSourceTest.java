package io.github.brainboxemb.eventtiming.timingpoint.platform.time;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ClockTimeSourceTest {

    @Test
    public void readsAbsoluteTimeFromInjectedClock() {
        Clock clock =
                Clock.fixed(
                        Instant.parse(
                                "2026-10-07T06:00:00Z"),
                        ZoneOffset.UTC);

        TimeSource timeSource =
                new ClockTimeSource(
                        clock);

        assertEquals(
                TimingTimestamp.parse(
                        "2026-10-07T06:00:00Z"),
                timeSource.now());
    }
}
