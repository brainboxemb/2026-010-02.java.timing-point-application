package io.github.brainboxemb.eventtiming.timingpoint.platform.environment;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlatformEnvironmentTest {

    @Test
    public void normalizesCommonOperatingSystemNames() {
        assertEquals(
                PlatformEnvironment.OperatingSystem.WINDOWS,
                PlatformEnvironment.detectOperatingSystem(
                        "Windows 11"));
        assertEquals(
                PlatformEnvironment.OperatingSystem.LINUX,
                PlatformEnvironment.detectOperatingSystem(
                        "Linux"));
        assertEquals(
                PlatformEnvironment.OperatingSystem.MACOS,
                PlatformEnvironment.detectOperatingSystem(
                        "Mac OS X"));
        assertEquals(
                PlatformEnvironment.OperatingSystem.MACOS,
                PlatformEnvironment.detectOperatingSystem(
                        "Darwin"));
        assertEquals(
                PlatformEnvironment.OperatingSystem.OTHER,
                PlatformEnvironment.detectOperatingSystem(
                        "UnknownOS"));
    }
}
