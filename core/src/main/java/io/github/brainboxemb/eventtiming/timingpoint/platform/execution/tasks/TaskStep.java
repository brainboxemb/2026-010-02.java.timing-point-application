package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks;

import java.time.Duration;

/**
 * Continuation decision returned by one {@link YieldingTask} step.
 */
public final class TaskStep {

    /** Stable continuation categories interpreted by a YieldingTaskRunner. */
    public enum Type {
        /** Re-admit the task at the back of the same serial lane immediately. */
        AGAIN,
        /** Re-admit the task only after the supplied delay. */
        AFTER,
        /** Complete the current yielding-task run. */
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

    /** Creates one immutable continuation decision. */
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

    /** Completes the yielding task. */
    public static TaskStep done() {
        return DONE;
    }

    /** Returns the continuation category for this step. */
    public Type type() {
        return type;
    }

    /** Returns the delay for AFTER, or null for AGAIN/DONE. */
    public Duration delay() {
        return delay;
    }
}
