package io.github.brainboxemb.eventtiming.timingpoint.platform.environment;

import java.time.Clock;

/**
 * Small process/platform time boundary shared by runtime-composed components.
 *
 * <p>The two clocks deliberately have different semantics:</p>
 *
 * <ul>
 *   <li>{@link #clock()} is absolute wall-clock time. I/O adapters use it when
 *       an external fact must receive an event timestamp.</li>
 *   <li>{@link #monotonicClock()} is process-local elapsed time. It is used for
 *       durations, filtering windows, metrics and timeout measurement only.</li>
 * </ul>
 *
 * <p>This class is intentionally not a general service locator. Filesystem,
 * networking and other operating-system facilities keep their own explicit
 * adapters/dependencies.</p>
 */
public final class PlatformEnvironment {

    private final Clock clock;
    private final MonotonicClock monotonicClock;

    public PlatformEnvironment(
            Clock clock,
            MonotonicClock monotonicClock) {
        if (clock == null) {
            throw new IllegalArgumentException(
                    "clock must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException(
                    "monotonicClock must not be null");
        }
        this.clock = clock;
        this.monotonicClock = monotonicClock;
    }

    /** Normal runtime environment backed by the operating-system/JVM clocks. */
    public static PlatformEnvironment system() {
        return new PlatformEnvironment(
                Clock.systemUTC(),
                SystemMonotonicClock.INSTANCE);
    }

    /** Absolute wall clock for externally meaningful timestamps. */
    public Clock clock() {
        return clock;
    }

    /** Monotonic elapsed-time source; values are never persisted as event time. */
    public MonotonicClock monotonicClock() {
        return monotonicClock;
    }
}
