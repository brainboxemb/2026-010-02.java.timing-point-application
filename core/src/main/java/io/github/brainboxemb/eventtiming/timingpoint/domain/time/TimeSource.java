package io.github.brainboxemb.eventtiming.timingpoint.domain.time;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

/**
 * Domain-facing source of absolute timing time.
 *
 * <p>The source is intentionally not tied to TimingSystem, TimingNode or a
 * platform implementation. Runtime composition decides which components share
 * one source. Components that must use the same corrected timing basis receive
 * the same TimeSource instance.</p>
 *
 * <p>Elapsed-time measurement does not use this contract; monotonic time is a
 * separate platform concern.</p>
 */
@FunctionalInterface
public interface TimeSource {

    TimingTimestamp now();
}
