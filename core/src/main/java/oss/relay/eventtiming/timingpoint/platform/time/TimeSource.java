package oss.relay.eventtiming.timingpoint.platform.time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Shared source of absolute timing time.
 *
 * <p>Runtime composition decides which components share one TimeSource instance.
 * The source is deliberately not tied to TimingSystem, TimingNode or one device,
 * so Domain and I/O components that belong to the same timing context can use
 * the same corrected absolute-time basis.</p>
 *
 * <p>The source also exposes the configured civil-time zone used when an external
 * value omits an offset. Runtime composition owns that choice; domain code must
 * not fall back to the host default zone.</p>
 *
 * <p>Elapsed-time measurement does not use this contract; monotonic time is a
 * separate platform capability.</p>
 */
@FunctionalInterface
public interface TimeSource {

    /** Current absolute timing instant. */
    Instant now();

    /**
     * Configured civil-time zone for values that do not carry an explicit offset.
     *
     * <p>Simple/custom sources default to UTC. ClockTimeSource overrides this with the zone
     * carried by its injected Clock, which Runtime can replace or re-zone explicitly.</p>
     */
    default ZoneId zone() {
        return ZoneOffset.UTC;
    }
}
