package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs bounded result-bearing and scheduled tasks on one
 * {@link SerialScheduledExecutor}.
 *
 * <p>This class centralizes task handling that would otherwise be repeated by
 * components: cooperative multi-step task continuation, bounded result waiting,
 * cancellation propagation, asynchronous completion and delayed continuations.
 * It owns neither a physical worker nor a scheduler; those remain external to
 * the wrapped logical lane.</p>
 *
 * <p>Domain/I/O components map {@link OperationException} to their own failure
 * semantics instead of putting component-specific policy in this platform type.</p>
 */
public final class ScheduledTaskRunner implements CooperativeTaskRunner {

    public enum FailureReason {
        OVERLOADED,
        TIMEOUT,
        INTERRUPTED,
        EXECUTION_FAILURE
    }

    public static final class OperationException
            extends RuntimeException {
        private final FailureReason reason;

        public OperationException(
                FailureReason reason,
                String message,
                Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public FailureReason reason() {
            return reason;
        }
    }

    private final SerialScheduledExecutor lane;
    private final long timeoutNanos;

    public ScheduledTaskRunner(
            SerialScheduledExecutor lane,
            Duration timeout) {
        if (lane == null) {
            throw new IllegalArgumentException(
                    "lane must not be null");
        }
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "timeout must be positive");
        }

        this.lane = lane;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "timeout is too large",
                    ex);
        }
    }

    public void start() {
        lane.start();
    }

    public boolean execute(
            Runnable action) {
        return lane.execute(action);
    }

    /**
     * Runs one cooperative state-machine task until it returns
     * {@link TaskStep#done()} or fails.
     *
     * <p>Each invocation of {@link CooperativeTask#runStep()} is one logical
     * turn. {@link TaskStep#again()} yields the lane and re-admits the task at
     * the back of the same serial queue. {@link TaskStep#after(Duration)}
     * releases the physical worker and re-admits the task when the delay
     * expires.</p>
     */
    @Override
    public CompletableFuture<Void> runTask(
            CooperativeTask task) {
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }

        CompletableFuture<Void> result =
                new CompletableFuture<Void>();
        AtomicReference<SerialScheduledExecutor.ScheduledResult<?>> active =
                new AtomicReference<SerialScheduledExecutor.ScheduledResult<?>>();

        result.whenComplete(
                (ignored, failure) -> {
                    if (!result.isCancelled()) {
                        return;
                    }

                    SerialScheduledExecutor.ScheduledResult<?> scheduled =
                            active.get();
                    if (scheduled != null) {
                        scheduled.futureResult()
                                .cancel(true);
                    }
                });

        scheduleTaskStep(
                task,
                0L,
                result,
                active);
        return result;
    }

    private void scheduleTaskStep(
            CooperativeTask task,
            long delayNanos,
            CompletableFuture<Void> result,
            AtomicReference<SerialScheduledExecutor.ScheduledResult<?>> active) {
        if (result.isDone()) {
            return;
        }

        final SerialScheduledExecutor.ScheduledResult<TaskStep> scheduled;
        try {
            scheduled =
                    lane.submitAfter(
                            task::runStep,
                            delayNanos);
            active.set(
                    scheduled);
        } catch (RuntimeException ex) {
            result.completeExceptionally(
                    failure(
                            FailureReason.OVERLOADED,
                            "Serial scheduled operation lane rejected cooperative task step",
                            ex));
            return;
        }

        if (result.isCancelled()) {
            scheduled.futureResult()
                    .cancel(true);
            return;
        }

        scheduled.completion()
                .whenComplete(
                        (step, stepFailure) -> {
                            if (result.isDone()) {
                                return;
                            }
                            if (stepFailure != null) {
                                result.completeExceptionally(
                                        stepFailure);
                                return;
                            }
                            if (step == null) {
                                result.completeExceptionally(
                                        new IllegalStateException(
                                                "cooperative task returned no TaskStep"));
                                return;
                            }

                            switch (step.type()) {
                                case DONE:
                                    result.complete(
                                            null);
                                    return;
                                case AGAIN:
                                    scheduleTaskStep(
                                            task,
                                            0L,
                                            result,
                                            active);
                                    return;
                                case AFTER:
                                    Duration delay =
                                            step.delay();
                                    if (delay == null) {
                                        result.completeExceptionally(
                                                new IllegalStateException(
                                                        "AFTER task step requires a delay"));
                                        return;
                                    }
                                    final long nextDelayNanos;
                                    try {
                                        nextDelayNanos =
                                                delay.toNanos();
                                    } catch (ArithmeticException ex) {
                                        result.completeExceptionally(
                                                new IllegalArgumentException(
                                                        "task delay is too large",
                                                        ex));
                                        return;
                                    }
                                    scheduleTaskStep(
                                            task,
                                            nextDelayNanos,
                                            result,
                                            active);
                                    return;
                                default:
                                    result.completeExceptionally(
                                            new IllegalStateException(
                                                    "Unsupported cooperative task step "
                                                            + step.type()));
                            }
                        });
    }

    /**
     * Executes one result-bearing task asynchronously on this serial lane.
     *
     * <p>The zero-delay scheduled result keeps the actual lane Future available,
     * so a caller that later times out can interrupt a running provider call.</p>
     */
    public CompletableFuture<Void> runAsync(
            Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException(
                    "action must not be null");
        }

        try {
            SerialScheduledExecutor.ScheduledResult<Void> scheduled =
                    lane.submitAfter(
                            () -> {
                                action.run();
                                return null;
                            },
                            0L);
            return bridge(
                    scheduled);
        } catch (RuntimeException ex) {
            return failedFuture(
                    failure(
                            FailureReason.OVERLOADED,
                            "Serial scheduled operation lane rejected asynchronous work",
                            ex));
        }
    }

    /**
     * Executes a begin/complete control sequence without occupying the physical
     * worker while the configured delay elapses.
     *
     * <p>Both begin and completion are result-bearing lane tasks. The elapsed
     * delay exists only as a timer registration between those tasks, so the
     * physical worker remains available and a control timeout can still cancel
     * whichever provider step is currently running.</p>
     */
    public CompletableFuture<Void> runDelayed(
            Callable<Duration> begin,
            Runnable complete) {
        if (begin == null) {
            throw new IllegalArgumentException(
                    "begin must not be null");
        }
        if (complete == null) {
            throw new IllegalArgumentException(
                    "complete must not be null");
        }

        CompletableFuture<Void> result =
                new CompletableFuture<Void>();
        AtomicReference<SerialScheduledExecutor.ScheduledResult<?>> active =
                new AtomicReference<SerialScheduledExecutor.ScheduledResult<?>>();

        result.whenComplete((ignored, failure) -> {
            if (!result.isCancelled()) {
                return;
            }

            SerialScheduledExecutor.ScheduledResult<?> scheduled =
                    active.get();
            if (scheduled != null) {
                scheduled.futureResult()
                        .cancel(true);
            }
        });

        final SerialScheduledExecutor.ScheduledResult<Duration> beginTask;
        try {
            beginTask =
                    lane.submitAfter(
                            begin,
                            0L);
            active.set(beginTask);
        } catch (RuntimeException ex) {
            result.completeExceptionally(
                    failure(
                            FailureReason.OVERLOADED,
                            "Serial scheduled operation lane rejected delayed begin work",
                            ex));
            return result;
        }

        beginTask.completion()
                .whenComplete(
                        (delay, beginFailure) -> {
                            if (result.isDone()) {
                                return;
                            }
                            if (beginFailure != null) {
                                result.completeExceptionally(
                                        beginFailure);
                                return;
                            }
                            if (delay == null) {
                                result.complete(null);
                                return;
                            }
                            if (delay.isNegative()) {
                                result.completeExceptionally(
                                        new IllegalStateException(
                                                "delayed control step returned a negative delay"));
                                return;
                            }

                            try {
                                SerialScheduledExecutor.ScheduledResult<Void> completeTask =
                                        lane.submitAfter(
                                                () -> {
                                                    complete.run();
                                                    return null;
                                                },
                                                delay.toNanos());
                                active.set(completeTask);

                                if (result.isCancelled()) {
                                    completeTask.futureResult()
                                            .cancel(true);
                                    return;
                                }

                                completeTask.completion()
                                        .whenComplete(
                                                (ignored, completeFailure) -> {
                                                    if (result.isDone()) {
                                                        return;
                                                    }
                                                    if (completeFailure == null) {
                                                        result.complete(null);
                                                    } else {
                                                        result.completeExceptionally(
                                                                completeFailure);
                                                    }
                                                });
                            } catch (RuntimeException ex) {
                                result.completeExceptionally(
                                        failure(
                                                FailureReason.OVERLOADED,
                                                "Serial scheduled operation lane rejected delayed completion work",
                                                ex));
                            }
                        });

        return result;
    }

    /**
     * Waits for one asynchronous control sequence using the configured
     * result-bearing control timeout.
     */
    public void await(
            CompletableFuture<Void> future) {
        if (future == null) {
            throw new IllegalArgumentException(
                    "future must not be null");
        }
        awaitFuture(
                future);
    }

    public void run(
            Runnable action) {
        SerialExecutor.SubmitResult<Void> submission =
                lane.submit(() -> {
                    action.run();
                    return null;
                });

        switch (submission.admission()) {
            case FULL:
                throw failure(
                        FailureReason.OVERLOADED,
                        "Serial scheduled operation lane is full",
                        null);
            case NOT_RUNNING:
                throw failure(
                        FailureReason.OVERLOADED,
                        "Serial scheduled operation lane is not running",
                        lane.failure());
            case ACCEPTED:
                awaitFuture(
                        submission.futureResult());
                return;
            default:
                throw new IllegalStateException(
                        "Unsupported control admission "
                                + submission.admission());
        }
    }

    public SerialScheduledExecutor.ScheduledRegistration scheduleWithFixedDelay(
            Runnable action,
            Duration delay) {
        if (delay == null
                || delay.isZero()
                || delay.isNegative()) {
            throw new IllegalArgumentException(
                    "delay must be positive");
        }

        return lane.scheduleWithFixedDelay(
                action,
                delay.toNanos());
    }

    public boolean isNew() {
        return lane.state()
                == SerialScheduledExecutor.State.NEW;
    }

    public boolean isRunning() {
        return lane.state()
                == SerialScheduledExecutor.State.RUNNING;
    }

    public Throwable failure() {
        return lane.failure();
    }

    public void close() {
        lane.close();
    }

    private void awaitFuture(
            Future<Void> future) {
        try {
            future.get(
                    timeoutNanos,
                    TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw failure(
                    FailureReason.TIMEOUT,
                    "Serial scheduled operation timed out",
                    ex);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(
                    FailureReason.INTERRUPTED,
                    "Serial scheduled operation was interrupted",
                    ex);
        } catch (CancellationException ex) {
            throw failure(
                    FailureReason.OVERLOADED,
                    "Serial scheduled operation was cancelled before completion",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause =
                    ex.getCause() == null
                            ? ex
                            : ex.getCause();

            if (cause instanceof OperationException) {
                throw (OperationException) cause;
            }
            if (cause instanceof RejectedExecutionException) {
                throw failure(
                        FailureReason.OVERLOADED,
                        "Backing worker rejected serial scheduled operation",
                        cause);
            }

            throw failure(
                    FailureReason.EXECUTION_FAILURE,
                    "Serial scheduled operation failed",
                    cause);
        }
    }

    private static <R> CompletableFuture<R> bridge(
            SerialScheduledExecutor.ScheduledResult<R> scheduled) {
        CompletableFuture<R> result =
                new CompletableFuture<R>();

        result.whenComplete(
                (ignored, failure) -> {
                    if (result.isCancelled()) {
                        scheduled.futureResult()
                                .cancel(true);
                    }
                });

        scheduled.completion()
                .whenComplete(
                        (value, failure) -> {
                            if (result.isDone()) {
                                return;
                            }
                            if (failure == null) {
                                result.complete(value);
                            } else {
                                result.completeExceptionally(
                                        failure);
                            }
                        });

        return result;
    }

    private static <R> CompletableFuture<R> failedFuture(
            Throwable failure) {
        CompletableFuture<R> result =
                new CompletableFuture<R>();
        result.completeExceptionally(
                failure);
        return result;
    }

    private static OperationException failure(
            FailureReason reason,
            String message,
            Throwable cause) {
        return new OperationException(
                reason,
                message,
                cause);
    }
}
