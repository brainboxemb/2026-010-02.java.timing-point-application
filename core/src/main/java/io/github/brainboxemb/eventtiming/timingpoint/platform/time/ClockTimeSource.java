package io.github.brainboxemb.eventtiming.timingpoint.platform.time;

import java.time.Clock;
import java.time.Instant;

/**
 * TimeSource backed directly by an absolute Java wall clock.
 *
 * <p>This implementation applies no timing correction of its own. Runtime
 * normally supplies the Clock from PlatformEnvironment. A later
 * corrected/programmed TimeSource can implement the same contract and be shared
 * by the Domain and I/O components in one timing context.</p>
 */
public final class ClockTimeSource implements TimeSource {
    private final Clock clock;

    public ClockTimeSource(
            Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException(
                    "clock must not be null");
        }
        this.clock = clock;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }
}
