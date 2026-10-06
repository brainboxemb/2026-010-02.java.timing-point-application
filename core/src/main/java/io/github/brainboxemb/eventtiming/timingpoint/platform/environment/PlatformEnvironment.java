package io.github.brainboxemb.eventtiming.timingpoint.platform.environment;

import java.time.Clock;
import java.util.Locale;

/**
 * Small process/platform boundary shared by runtime-composed components.
 *
 * <p>The environment deliberately exposes only process/platform facts that are
 * needed by composition or runtime behaviour. It is not a general service
 * locator.</p>
 *
 * <p>The two clocks have different semantics:</p>
 *
 * <ul>
 *   <li>{@link #clock()} is absolute wall-clock time. I/O adapters use it when
 *       an external fact must receive an event timestamp.</li>
 *   <li>{@link #monotonicClock()} is process-local elapsed time. It is used for
 *       durations, filtering windows, metrics and timeout measurement only.</li>
 * </ul>
 *
 * <p>{@link #operatingSystem()} exposes one normalized operating-system family
 * so Runtime composition does not scatter direct JVM system-property checks.</p>
 */
public final class PlatformEnvironment {

    public enum OperatingSystem {
        WINDOWS,
        LINUX,
        MACOS,
        OTHER
    }

    private final Clock clock;
    private final MonotonicClock monotonicClock;
    private final OperatingSystem operatingSystem;

    /**
     * Creates an environment using the current JVM operating-system identity.
     *
     * <p>The overload remains useful for tests/components that only need
     * deterministic clocks. Tests of platform-dependent composition should use
     * the three-argument constructor.</p>
     */
    public PlatformEnvironment(
            Clock clock,
            MonotonicClock monotonicClock) {
        this(
                clock,
                monotonicClock,
                detectOperatingSystem(
                        System.getProperty(
                                "os.name",
                                "")));
    }

    public PlatformEnvironment(
            Clock clock,
            MonotonicClock monotonicClock,
            OperatingSystem operatingSystem) {
        if (clock == null) {
            throw new IllegalArgumentException(
                    "clock must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException(
                    "monotonicClock must not be null");
        }
        if (operatingSystem == null) {
            throw new IllegalArgumentException(
                    "operatingSystem must not be null");
        }

        this.clock = clock;
        this.monotonicClock = monotonicClock;
        this.operatingSystem = operatingSystem;
    }

    /** Normal runtime environment backed by operating-system/JVM facilities. */
    public static PlatformEnvironment system() {
        return new PlatformEnvironment(
                Clock.systemUTC(),
                SystemMonotonicClock.INSTANCE,
                detectOperatingSystem(
                        System.getProperty(
                                "os.name",
                                "")));
    }

    /** Absolute wall clock for externally meaningful timestamps. */
    public Clock clock() {
        return clock;
    }

    /** Monotonic elapsed-time source; values are never persisted as event time. */
    public MonotonicClock monotonicClock() {
        return monotonicClock;
    }

    /** Normalized operating-system family used by Runtime composition. */
    public OperatingSystem operatingSystem() {
        return operatingSystem;
    }

    static OperatingSystem detectOperatingSystem(
            String osName) {
        String normalized =
                osName == null
                        ? ""
                        : osName.trim()
                                .toLowerCase(
                                        Locale.ROOT);

        if (normalized.contains("win")) {
            return OperatingSystem.WINDOWS;
        }
        if (normalized.contains("linux")) {
            return OperatingSystem.LINUX;
        }
        if (normalized.contains("mac")
                || normalized.contains("darwin")) {
            return OperatingSystem.MACOS;
        }
        return OperatingSystem.OTHER;
    }
}
