package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs cooperative tasks on one {@link SerialScheduledExecutor}.
 *
 * <p>The runner owns cooperative continuation, cancellation propagation and
 * bounded waiting for a complete task run. The task owns its operation/control
 * state. The wrapped lane owns serial admission and delayed scheduling.</p>
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

    /**
     * One active cooperative run.
     *
     * <p>This private context gives asynchronous boundaries names. Cancellation
     * of the public completion cancels the currently admitted/running lane step;
     * completion of a lane step decides whether the task is done or must be
     * admitted again. No component/domain state is kept here.</p>
     */
    private final class CooperativeRun {
        private final CooperativeTask task;
        private final CompletableFuture<Void> result =
                new CompletableFuture<Void>();
        private final AtomicReference<
                SerialScheduledExecutor.ScheduledResult<?>> active =
                        new AtomicReference<
                                SerialScheduledExecutor.ScheduledResult<?>>();

        private CooperativeRun(
                CooperativeTask task) {
            this.task = task;
            result.whenComplete(
                    this::runCompleted);
        }

        private CompletableFuture<Void> start() {
            scheduleStep(
                    0L);
            return result;
        }

        /**
         * Propagates caller cancellation to whichever lane step is current.
         */
        private void runCompleted(
                Void ignored,
                Throwable ignoredFailure) {
            if (!result.isCancelled()) {
                return;
            }

            SerialScheduledExecutor.ScheduledResult<?> scheduled =
                    active.get();
            if (scheduled != null) {
                scheduled.futureResult()
                        .cancel(true);
            }
        }

        /**
         * Admits one task turn. Delays are represented by the scheduled lane;
         * this method never sleeps a worker.
         */
        private void scheduleStep(
                long delayNanos) {
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
                            this::stepCompleted);
        }

        /**
         * Handles completion of one admitted task turn.
         */
        private void stepCompleted(
                TaskStep step,
                Throwable stepFailure) {
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
                    scheduleStep(
                            0L);
                    return;

                case AFTER:
                    scheduleAfter(
                            step.delay());
                    return;

                default:
                    result.completeExceptionally(
                            new IllegalStateException(
                                    "Unsupported cooperative task step "
                                            + step.type()));
            }
        }

        private void scheduleAfter(
                Duration delay) {
            if (delay == null) {
                result.completeExceptionally(
                        new IllegalStateException(
                                "AFTER task step requires a delay"));
                return;
            }

            final long delayNanos;
            try {
                delayNanos = delay.toNanos();
            } catch (ArithmeticException ex) {
                result.completeExceptionally(
                        new IllegalArgumentException(
                                "task delay is too large",
                                ex));
                return;
            }

            scheduleStep(
                    delayNanos);
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

    /**
     * Admits one ordinary short action to the wrapped lane.
     *
     * <p>This is intentionally a direct lane operation, not another task model.
     * It exists for sibling work that only needs serial ordering.</p>
     */
    public boolean execute(
            Runnable action) {
        return lane.execute(
                action);
    }

    /**
     * Runs one cooperative task until it returns
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

        return new CooperativeRun(
                task)
                .start();
    }

    /**
     * Waits for one cooperative task run using the configured control timeout.
     *
     * <p>Timeout/interruption cancels the public completion. CooperativeRun then
     * propagates that cancellation to the current scheduled lane step.</p>
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
