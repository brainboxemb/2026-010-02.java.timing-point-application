package io.github.brainboxemb.eventtiming.timingpoint.domain.time;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.Clock;

/**
 * TimeSource backed directly by a Java absolute wall clock.
 *
 * <p>This implementation applies no timing correction of its own. Runtime
 * composition supplies the Clock, normally from PlatformEnvironment. A later
 * corrected/programmed TimeSource can implement the same domain contract
 * without changing TimingNode consumers.</p>
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
    public TimingTimestamp now() {
        return new TimingTimestamp(
                clock.instant());
    }
}
