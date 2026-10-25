package oss.relay.eventtiming.timingpoint.runtime;

import oss.relay.eventtiming.timingpoint.platform.environment.PlatformEnvironment;
import oss.relay.eventtiming.timingpoint.platform.time.TimeSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

public class RuntimeTimeSourcesTest {

    @Test
    public void createsIndependentTimingSourceInstancesFromPlatformClock() {
        PlatformEnvironment platform =
                new PlatformEnvironment(
                        Clock.fixed(
                                Instant.parse(
                                        "2026-10-07T06:00:00Z"),
                                ZoneOffset.UTC),
                        System::nanoTime);
        RuntimeTimeSources sources =
                new RuntimeTimeSources(
                        platform);

        TimeSource first =
                sources.createTimeSource();
        TimeSource second =
                sources.createTimeSource();

        assertNotSame(
                first,
                second);
        assertEquals(
                Instant.parse(
                        "2026-10-07T06:00:00Z"),
                first.now());
        assertEquals(
                first.now(),
                second.now());
        assertEquals(
                ZoneOffset.UTC,
                first.zone());
        assertEquals(
                first.zone(),
                second.zone());
    }
}
