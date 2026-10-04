package io.github.brainboxemb.eventtiming.timingpoint.platform.environment;

/** JDK-backed monotonic clock used by the normal runtime. */
public enum SystemMonotonicClock implements MonotonicClock {
    INSTANCE;

    @Override
    public long nowNanos() {
        return System.nanoTime();
    }
}
