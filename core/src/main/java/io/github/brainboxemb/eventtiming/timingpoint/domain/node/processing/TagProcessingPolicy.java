package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import java.time.Duration;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Policy used while converting repeated antenna reads into registrations.
 *
 * <p>Quiet timeout and maximum passage duration are elapsed-time rules. The
 * passage filter compares them directly with the monotonic nanosecond value
 * captured in each TagObservation. Absolute event time never participates in
 * passage-boundary decisions.</p>
 *
 * <p>The same quiet timeout is also used to flush an otherwise open passage
 * when no later observation arrives. Duplicate-window and sweep values are
 * likewise runtime durations rather than wall-clock timestamps.</p>
 */
public final class TagProcessingPolicy {
    private static final Duration DEFAULT_QUIET_TIMEOUT = Duration.ofMillis(250L);
    private static final Duration DEFAULT_MAX_PASSAGE_DURATION = Duration.ofMillis(1000L);
    private static final Duration DEFAULT_DUPLICATE_WINDOW = Duration.ofMillis(15000L);
    private static final Duration DEFAULT_SWEEP_CADENCE = Duration.ofMillis(50L);
    private static final int DEFAULT_OBSERVATION_QUEUE_CAPACITY = 256;

    private final long quietTimeoutNanos;
    private final long maxPassageDurationNanos;
    private final long duplicateWindowNanos;
    private final long sweepCadenceNanos;
    private final int observationQueueCapacity;

    /** Creates an immutable passage/duplicate processing policy. */
    public TagProcessingPolicy(
            Duration quietTimeout,
            Duration maxPassageDuration,
            Duration duplicateWindow,
            Duration sweepCadence,
            int observationQueueCapacity) {
        quietTimeoutNanos = positiveNanos(quietTimeout, "quietTimeout");
        maxPassageDurationNanos = positiveNanos(maxPassageDuration, "maxPassageDuration");
        duplicateWindowNanos = nonNegativeNanos(duplicateWindow, "duplicateWindow");
        sweepCadenceNanos = positiveNanos(sweepCadence, "sweepCadence");
        failIfInvalidArgument(observationQueueCapacity > 0, "observationQueueCapacity must be positive");
        this.observationQueueCapacity = observationQueueCapacity;
    }

    /** Returns the built-in first-executable policy baseline. */
    public static TagProcessingPolicy defaults() {
        return new TagProcessingPolicy(
                DEFAULT_QUIET_TIMEOUT,
                DEFAULT_MAX_PASSAGE_DURATION,
                DEFAULT_DUPLICATE_WINDOW,
                DEFAULT_SWEEP_CADENCE,
                DEFAULT_OBSERVATION_QUEUE_CAPACITY);
    }

    /** Returns the elapsed quiet period that closes or flushes a passage. */
    public long quietTimeoutNanos() {
        return quietTimeoutNanos;
    }

    /** Returns the maximum elapsed monotonic span allowed for one passage. */
    public long maxPassageDurationNanos() {
        return maxPassageDurationNanos;
    }

    /** Returns the elapsed suppression window after one RegistrationId was accepted. */
    public long duplicateWindowNanos() {
        return duplicateWindowNanos;
    }

    /** Returns the cadence used for scheduled passage/duplicate housekeeping. */
    public long sweepCadenceNanos() {
        return sweepCadenceNanos;
    }

    /** Returns the bounded TagProcessor observation queue capacity. */
    public int observationQueueCapacity() {
        return observationQueueCapacity;
    }

    /** Compares all configured processing-policy values. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TagProcessingPolicy)) {
            return false;
        }
        TagProcessingPolicy that = (TagProcessingPolicy) other;
        return quietTimeoutNanos == that.quietTimeoutNanos
                && maxPassageDurationNanos == that.maxPassageDurationNanos
                && duplicateWindowNanos == that.duplicateWindowNanos
                && sweepCadenceNanos == that.sweepCadenceNanos
                && observationQueueCapacity == that.observationQueueCapacity;
    }

    /** Returns a hash consistent with policy value equality. */
    @Override
    public int hashCode() {
        int result = (int) (quietTimeoutNanos ^ (quietTimeoutNanos >>> 32));
        result = 31 * result
                + (int) (maxPassageDurationNanos ^ (maxPassageDurationNanos >>> 32));
        result = 31 * result
                + (int) (duplicateWindowNanos ^ (duplicateWindowNanos >>> 32));
        result = 31 * result
                + (int) (sweepCadenceNanos ^ (sweepCadenceNanos >>> 32));
        result = 31 * result + observationQueueCapacity;
        return result;
    }

    /** Converts a required positive duration to the monotonic nanosecond unit used by processing. */
    private static long positiveNanos(Duration value, String name) {
        long nanos = nonNegativeNanos(value, name);
        failIfInvalidArgument(nanos > 0L, "%s must be positive", name);
        return nanos;
    }

    /** Converts a required non-negative duration to nanoseconds and rejects overflow. */
    private static long nonNegativeNanos(Duration value, String name) {
        failIfInvalidArgument(value != null, "%s must not be null", name);
        failIfInvalidArgument(!value.isNegative(), "%s must not be negative", name);
        try {
            return value.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(name + " is too large", ex);
        }
    }
}
