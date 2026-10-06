package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.time.Duration;

/**
 * Continuation decision returned by one {@link CooperativeTask} step.
 */
public final class TaskStep {

    public enum Type {
        AGAIN,
        AFTER,
        DONE
    }

    private static final TaskStep AGAIN =
            new TaskStep(
                    Type.AGAIN,
                    null);
    private static final TaskStep DONE =
            new TaskStep(
                    Type.DONE,
                    null);

    private final Type type;
    private final Duration delay;

    private TaskStep(
            Type type,
            Duration delay) {
        this.type = type;
        this.delay = delay;
    }

    /**
     * Yields the lane and re-admits the task at the back of the same serial
     * queue.
     */
    public static TaskStep again() {
        return AGAIN;
    }

    /**
     * Releases the lane and re-admits the task after the requested delay.
     */
    public static TaskStep after(
            Duration delay) {
        if (delay == null
                || delay.isNegative()) {
            throw new IllegalArgumentException(
                    "delay must not be negative");
        }
        if (delay.isZero()) {
            return AGAIN;
        }
        return new TaskStep(
                Type.AFTER,
                delay);
    }

    /** Completes the cooperative task. */
    public static TaskStep done() {
        return DONE;
    }

    public Type type() {
        return type;
    }

    public Duration delay() {
        return delay;
    }
}
