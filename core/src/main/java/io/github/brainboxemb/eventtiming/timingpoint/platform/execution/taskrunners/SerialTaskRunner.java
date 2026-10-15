package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.taskrunners;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.scheduling.LaneScheduler;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs {@link YieldingTask}s on one existing {@link SerialLane}.
 *
 * <p>Without a scheduler the runner supports immediate {@link TaskStep#again()}
 * yielding. With a scheduler it also supports {@link TaskStep#after(Duration)}.
 * Scheduling never creates another lane: delayed steps are admitted back to the
 * same serial lane when their delay expires.</p>
 */
public final class SerialTaskRunner implements YieldingTaskRunner {

    public enum FailureReason {
        OVERLOADED,
        TIMEOUT,
        INTERRUPTED,
        EXECUTION_FAILURE
    }

    public static final class OperationException
            extends RuntimeException {
        private final FailureReason reason;

        /** Creates one task-runner failure with a stable reason and original cause. */
        public OperationException(
                FailureReason reason,
                String message,
                Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        /** Returns the stable category for this task-runner operation failure. */
        public FailureReason reason() {
            return reason;
        }
    }

    private final class TaskRun {
        private final YieldingTask task;
        private final CompletableFuture<Void> result =
                new CompletableFuture<Void>();
        private final AtomicReference<Future<?>> active =
                new AtomicReference<Future<?>>();

        /** Creates one cooperative task execution bound to this runner. */
        private TaskRun(
                YieldingTask task) {
            this.task = task;
            result.whenComplete(
                    this::runCompleted);
        }

        /** Starts the first cooperative task step and returns the completion handle. */
        private CompletableFuture<Void> start() {
            scheduleStep(
                    0L);
            return result;
        }

        /** Cancels admitted work only when the public task result was cancelled. */
        private void runCompleted(
                Void ignored,
                Throwable ignoredFailure) {
            if (result.isCancelled()) {
                Future<?> current =
                        active.get();
                if (current != null) {
                    current.cancel(true);
                }
            }
        }

        /** Chooses immediate or delayed execution for the next cooperative task step. */
        private void scheduleStep(
                long delayNanos) {
            if (result.isDone()) {
                return;
            }

            if (delayNanos > 0L) {
                scheduleDelayedStep(
                        delayNanos);
            } else {
                scheduleImmediateStep();
            }
        }

        /** Submits the next cooperative task step immediately to the serial lane. */
        private void scheduleImmediateStep() {
            CompletableFuture<TaskStep> completion =
                    new CompletableFuture<TaskStep>();

            Callable<TaskStep> stepExecution =
                    () -> executeStep(
                            task::runStep,
                            completion);

            final Future<TaskStep> future;
            try {
                future =
                        lane.submit(
                                stepExecution);
            } catch (RejectedExecutionException ex) {
                result.completeExceptionally(ex);
                return;
            }

            active.set(
                    future);
            if (result.isCancelled()) {
                future.cancel(true);
                return;
            }

            completion.whenComplete(
                    this::stepCompleted);
        }

        /** Schedules one delayed task step through the lane scheduler. */
        private void scheduleDelayedStep(
                long delayNanos) {
            if (scheduler == null) {
                result.completeExceptionally(
                        new IllegalStateException(
                                "TaskStep.AFTER requires a scheduler"));
                return;
            }

            final LaneScheduler.ScheduledOperation<TaskStep> scheduled;
            try {
                scheduled =
                        scheduler.submitAfter(
                                task::runStep,
                                delayNanos);
            } catch (RuntimeException ex) {
                result.completeExceptionally(
                        ex);
                return;
            }

            active.set(
                    scheduled.futureResult());
            if (result.isCancelled()) {
                scheduled.close();
                return;
            }

            scheduled.completion()
                    .whenComplete(
                            this::stepCompleted);
        }

        /** Executes one task step and completes its step Future with the returned instruction. */
        private TaskStep executeStep(
                Callable<TaskStep> operation,
                CompletableFuture<TaskStep> completion)
                throws Exception {
            try {
                TaskStep step =
                        operation.call();
                completion.complete(
                        step);
                return step;
            } catch (RuntimeException ex) {
                completion.completeExceptionally(
                        ex);
                throw ex;
            } catch (Exception ex) {
                completion.completeExceptionally(
                        ex);
                throw ex;
            } catch (Error error) {
                completion.completeExceptionally(
                        error);
                throw error;
            }
        }

        /** Interprets one completed TaskStep and schedules or completes the task accordingly. */
        private void stepCompleted(
                TaskStep step,
                Throwable stepFailure) {
            if (result.isDone()) {
                return;
            }

            if (stepFailure != null) {
                result.completeExceptionally(
                        stepFailure);
            } else if (step == null) {
                result.completeExceptionally(
                        new IllegalStateException(
                                "yielding task returned no TaskStep"));
            } else {
                switch (step.type()) {
                    case DONE:
                        result.complete(
                                null);
                        break;

                    case AGAIN:
                        scheduleStep(
                                0L);
                        break;

                    case AFTER:
                        scheduleAfter(
                                step.delay());
                        break;

                    default:
                        result.completeExceptionally(
                                new IllegalStateException(
                                        "Unsupported yielding task step "
                                                + step.type()));
                }
            }
        }

        /** Registers the next task step after the requested cooperative delay. */
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
                delayNanos =
                        delay.toNanos();
            } catch (ArithmeticException ex) {
                result.completeExceptionally(
                        new IllegalArgumentException(
                                "task delay is too large",
                                ex));
                return;
            }

            if (delayNanos <= 0L) {
                scheduleStep(
                        0L);
            } else {
                scheduleStep(
                        delayNanos);
            }
        }
    }

    private final SerialLane lane;
    private final LaneScheduler scheduler;
    private final long timeoutNanos;

    /**
     * Creates a runner for immediate yielding only.
     */
    public SerialTaskRunner(
            SerialLane lane) {
        this(
                lane,
                null,
                0L);
    }

    /**
     * Creates a runner that also supports delayed yielding and bounded waiting.
     */
    public SerialTaskRunner(
            SerialLane lane,
            ScheduledExecutorService scheduledExecutor,
            Duration timeout) {
        this(
                requireLane(lane),
                new LaneScheduler(
                        lane,
                        requireScheduler(scheduledExecutor)),
                timeoutNanos(timeout));
    }

    /** Creates the runner around an existing lane with optional scheduling and wait timeout. */
    private SerialTaskRunner(
            SerialLane lane,
            LaneScheduler scheduler,
            long timeoutNanos) {
        this.lane = requireLane(lane);
        this.scheduler = scheduler;
        this.timeoutNanos = timeoutNanos;
    }

    /** Starts one cooperative task and returns a Future completed when the task finishes. */
    @Override
    public CompletableFuture<Void> runTask(
            YieldingTask task) {
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }

        return new TaskRun(
                task)
                .start();
    }

    /** Starts the owned serial lane before tasks or ordinary operations are submitted. */
    public void start() {
        lane.start();
    }

    /** Queues one ordinary short operation on the same serial lane without waiting. */
    public void execute(
            Runnable operation) {
        lane.execute(operation);
    }

    /** Waits for one cooperative task completion using this runner's configured timeout. */
    public void await(
            CompletableFuture<Void> future) {
        if (timeoutNanos <= 0L) {
            throw new IllegalStateException(
                    "This SerialTaskRunner has no configured wait timeout");
        }
        if (future == null) {
            throw new IllegalArgumentException(
                    "future must not be null");
        }

        try {
            future.get(
                    timeoutNanos,
                    TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw failure(
                    FailureReason.TIMEOUT,
                    "Serial task timed out",
                    ex);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(
                    FailureReason.INTERRUPTED,
                    "Serial task wait was interrupted",
                    ex);
        } catch (CancellationException ex) {
            throw failure(
                    FailureReason.OVERLOADED,
                    "Serial task was cancelled before completion",
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
                        "Serial task could not be admitted",
                        cause);
            }

            throw failure(
                    FailureReason.EXECUTION_FAILURE,
                    "Serial task failed",
                    cause);
        }
    }

    /** Returns whether the underlying serial lane has never been started. */
    public boolean isNew() {
        return lane.state()
                == SerialLane.State.NEW;
    }

    /** Returns whether the underlying serial lane currently accepts work. */
    public boolean isRunning() {
        return lane.state()
                == SerialLane.State.RUNNING;
    }

    /** Returns the terminal lane failure used for task-runner diagnostics. */
    public Throwable failure() {
        return lane.failure();
    }

    /** Stops new serial-lane work after accepted work has drained. */
    public void close() {
        lane.close();
    }

    /** Validates the mandatory serial lane dependency. */
    private static SerialLane requireLane(
            SerialLane lane) {
        if (lane == null) {
            throw new IllegalArgumentException(
                    "lane must not be null");
        }
        return lane;
    }

    /** Validates the scheduler dependency when delayed task steps are enabled. */
    private static ScheduledExecutorService requireScheduler(
            ScheduledExecutorService scheduler) {
        if (scheduler == null) {
            throw new IllegalArgumentException(
                    "scheduledExecutor must not be null");
        }
        return scheduler;
    }

    /** Converts and validates the optional public wait timeout. */
    private static long timeoutNanos(
            Duration timeout) {
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "timeout must be positive");
        }
        try {
            return timeout.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "timeout is too large",
                    ex);
        }
    }

    /** Creates one stable task-runner operation failure. */
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
