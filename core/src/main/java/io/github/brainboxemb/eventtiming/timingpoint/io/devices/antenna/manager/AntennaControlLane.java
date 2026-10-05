package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.ControlException;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.FailureReason;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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
                await(
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

    private void await(
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
            Throwable cause = ex.getCause();
            throw failure(
                    FailureReason.PROVIDER_FAILURE,
                    "AntennaManager provider operation failed",
                    cause == null ? ex : cause);
        }
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
