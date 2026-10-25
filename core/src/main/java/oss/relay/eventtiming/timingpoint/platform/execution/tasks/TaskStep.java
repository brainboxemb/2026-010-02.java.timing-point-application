package oss.relay.eventtiming.timingpoint.platform.execution.tasks;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

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
        /** Re-admit the task only after the supplied asynchronous dependency completes. */
        AWAIT,
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
    private final CompletionStage<?> completion;

    /** Creates one immutable continuation decision. */
    private TaskStep(
            Type type,
            Duration delay) {
        this(type, delay, null);
    }

    /** Creates one immutable continuation decision. */
    private TaskStep(
            Type type,
            Duration delay,
            CompletionStage<?> completion) {
        this.type = type;
        this.delay = delay;
        this.completion = completion;
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

    /**
     * Releases the lane until the supplied asynchronous dependency completes.
     *
     * <p>The dependency result is not interpreted here. Completion only makes
     * the task eligible for another step; the task remains responsible for
     * reading or handling that dependency in its own state.</p>
     */
    public static TaskStep await(
            CompletionStage<?> completion) {
        if (completion == null) {
            throw new IllegalArgumentException(
                    "completion must not be null");
        }
        return new TaskStep(
                Type.AWAIT,
                null,
                completion);
    }

    /** Completes the yielding task. */
    public static TaskStep done() {
        return DONE;
    }

    /** Returns the continuation category for this step. */
    public Type type() {
        return type;
    }

    /** Returns the delay for AFTER, or null for other continuation types. */
    public Duration delay() {
        return delay;
    }

    /** Returns the dependency for AWAIT, or null for other continuation types. */
    public CompletionStage<?> completion() {
        return completion;
    }
}
