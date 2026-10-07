package io.github.brainboxemb.eventtiming.timingpoint.platform.time;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

/**
 * Shared source of absolute timing time.
 *
 * <p>Runtime composition decides which components share one TimeSource instance.
 * The source is deliberately not tied to TimingSystem, TimingNode or one device,
 * so Domain and I/O components that belong to the same timing context can use
 * the same corrected absolute-time basis.</p>
 *
 * <p>Elapsed-time measurement does not use this contract; monotonic time is a
 * separate platform capability.</p>
 */
@FunctionalInterface
public interface TimeSource {

    TimingTimestamp now();
}
