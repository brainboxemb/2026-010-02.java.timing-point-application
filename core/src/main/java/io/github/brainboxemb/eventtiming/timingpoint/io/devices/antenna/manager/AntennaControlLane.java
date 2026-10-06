package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.ControlException;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.FailureReason;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

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
 * Small adapter around the manager's one serial scheduled execution lane.
 *
 * <p>It centralizes admission and timeout handling so AntennaManager can express
 * antenna behaviour instead of Future/Executor mechanics. The physical worker
 * remains owned by Runtime; this class owns neither a thread nor a scheduler.</p>
 */
final class AntennaControlLane {

    private final SerialScheduledExecutor lane;
    private final long timeoutNanos;

    AntennaControlLane(
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

    void start() {
        lane.start();
    }

    boolean execute(
            Runnable action) {
        return lane.execute(action);
    }

    /**
     * Executes one result-bearing control action asynchronously on this serial lane.
     *
     * <p>The zero-delay scheduled result keeps the actual lane Future available,
     * so a caller that later times out can interrupt a running provider call.</p>
     */
    CompletableFuture<Void> runAsync(
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
                            "AntennaManager control lane rejected asynchronous work",
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
    CompletableFuture<Void> runDelayed(
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
                            "AntennaManager control lane rejected delayed begin work",
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
                                                "AntennaManager control lane rejected delayed completion work",
                                                ex));
                            }
                        });

        return result;
    }

    /**
     * Waits for one asynchronous control sequence using the configured
     * result-bearing control timeout.
     */
    void await(
            CompletableFuture<Void> future) {
        if (future == null) {
            throw new IllegalArgumentException(
                    "future must not be null");
        }
        awaitFuture(
                future);
    }

    void run(
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
                        "AntennaManager control lane is full",
                        null);
            case NOT_RUNNING:
                throw failure(
                        FailureReason.OVERLOADED,
                        "AntennaManager control lane is not running",
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

    SerialScheduledExecutor.ScheduledTask scheduleWithFixedDelay(
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

    boolean isNew() {
        return lane.state()
                == SerialScheduledExecutor.State.NEW;
    }

    boolean isRunning() {
        return lane.state()
                == SerialScheduledExecutor.State.RUNNING;
    }

    Throwable failure() {
        return lane.failure();
    }

    void close() {
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
                    "AntennaManager control operation timed out",
                    ex);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(
                    FailureReason.INTERRUPTED,
                    "AntennaManager control operation was interrupted",
                    ex);
        } catch (CancellationException ex) {
            throw failure(
                    FailureReason.OVERLOADED,
                    "AntennaManager control operation was cancelled before completion",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause =
                    ex.getCause() == null
                            ? ex
                            : ex.getCause();

            if (cause instanceof ControlException) {
                throw (ControlException) cause;
            }
            if (cause instanceof RejectedExecutionException) {
                throw failure(
                        FailureReason.OVERLOADED,
                        "AntennaManager shared I/O worker rejected control work",
                        cause);
            }

            throw failure(
                    FailureReason.PROVIDER_FAILURE,
                    "AntennaManager provider operation failed",
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

    static ControlException failure(
            FailureReason reason,
            String message,
            Throwable cause) {
        return new ControlException(
                reason,
                message,
                cause);
    }
}
