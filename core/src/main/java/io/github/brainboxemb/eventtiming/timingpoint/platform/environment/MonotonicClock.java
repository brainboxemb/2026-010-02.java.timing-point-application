package io.github.brainboxemb.eventtiming.timingpoint.platform.environment;

/**
 * Monotonic elapsed-time source for runtime measurement and timeout mechanics.
 *
 * <p>Values have meaning only as differences within one running JVM. They are
 * never Domain event timestamps and must not be persisted as TimingData.</p>
 */
@FunctionalInterface
public interface MonotonicClock {
    long nowNanos();
}
