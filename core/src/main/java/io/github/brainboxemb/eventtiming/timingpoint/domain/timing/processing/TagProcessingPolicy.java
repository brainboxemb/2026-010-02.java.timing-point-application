package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import java.time.Duration;

/**
 * Timing policy used while converting repeated antenna reads into registrations.
 *
 * <p>Observation timestamps are deliberately not used for these deadlines. The
 * timeouts describe elapsed process time, so callers provide a monotonic clock
 * separately.</p>
 */
public final class TagProcessingPolicy {
    private final long quietTimeoutNanos;
    private final long maxBurstDurationNanos;
    private final long duplicateWindowNanos;
    private final long sweepCadenceNanos;

    public TagProcessingPolicy(
            Duration quietTimeout,
            Duration maxBurstDuration,
            Duration duplicateWindow,
            Duration sweepCadence) {
        quietTimeoutNanos = positiveNanos(quietTimeout, "quietTimeout");
        maxBurstDurationNanos =
                positiveNanos(maxBurstDuration, "maxBurstDuration");
        duplicateWindowNanos =
                nonNegativeNanos(duplicateWindow, "duplicateWindow");
        sweepCadenceNanos = positiveNanos(sweepCadence, "sweepCadence");
    }

    public long quietTimeoutNanos() {
        return quietTimeoutNanos;
    }

    public long maxBurstDurationNanos() {
        return maxBurstDurationNanos;
    }

    public long duplicateWindowNanos() {
        return duplicateWindowNanos;
    }

    public long sweepCadenceNanos() {
        return sweepCadenceNanos;
    }

    private static long positiveNanos(Duration value, String name) {
        long nanos = nonNegativeNanos(value, name);
        if (nanos == 0L) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return nanos;
    }

    private static long nonNegativeNanos(Duration value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        try {
            return value.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(name + " is too large", ex);
        }
    }
}
