package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

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

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * Scheduled serial lane that can execute cooperative YieldingTasks.
 *
 * <p>Each task step is one queued lane item. AGAIN re-admits the next step at
 * the back of the queue; because the worker batch was fixed before that step ran,
 * the re-admitted step executes in a later worker run. AFTER uses this lane's
 * scheduling capability and also returns to the same serial lane.</p>
 */
public final class TaskSerialLane extends ScheduledSerialLane {

    public enum FailureReason {
        OVERLOADED,
        TIMEOUT,
        INTERRUPTED,
        EXECUTION_FAILURE
    }

    public static final class OperationException extends RuntimeException {
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

    private final class TaskRun {
        private final YieldingTask task;
        private final CompletableFuture<Void> result = new CompletableFuture<Void>();
        private final AtomicReference<Future<?>> active = new AtomicReference<Future<?>>();

        private TaskRun(YieldingTask task) {
            this.task = task;
            result.whenComplete(this::runCompleted);
        }

        private CompletableFuture<Void> start() {
            scheduleStep(0L);
            return result;
        }

        private void runCompleted(Void ignored, Throwable ignoredFailure) {
            if (result.isCancelled()) {
                Future<?> current = active.get();
                if (current != null) {
                    current.cancel(true);
                }
            }
        }

        private void scheduleStep(long delayNanos) {
            if (result.isDone()) {
                return;
            }
            if (delayNanos > 0L) {
                scheduleDelayedStep(delayNanos);
            } else {
                scheduleImmediateStep();
            }
        }

        private void scheduleImmediateStep() {
            CompletableFuture<TaskStep> completion = new CompletableFuture<TaskStep>();
            Callable<TaskStep> stepExecution =
                    () -> executeStep(task::runStep, completion);

            final Future<TaskStep> future;
            try {
                future = TaskSerialLane.this.submit(stepExecution);
            } catch (RejectedExecutionException ex) {
                result.completeExceptionally(ex);
                return;
            }

            active.set(future);
            if (result.isCancelled()) {
                future.cancel(true);
                return;
            }
            completion.whenComplete(this::stepCompleted);
        }

        private void scheduleDelayedStep(long delayNanos) {
            final CompletableFuture<TaskStep> scheduled;
            try {
                scheduled = TaskSerialLane.this.submitAfter(task::runStep, delayNanos);
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
                return;
            }

            active.set(scheduled);
            if (result.isCancelled()) {
                scheduled.cancel(true);
                return;
            }
            scheduled.whenComplete(this::stepCompleted);
        }

        private TaskStep executeStep(
                Callable<TaskStep> operation,
                CompletableFuture<TaskStep> completion)
                throws Exception {
            try {
                TaskStep step = operation.call();
                completion.complete(step);
                return step;
            } catch (RuntimeException ex) {
                completion.completeExceptionally(ex);
                throw ex;
            } catch (Exception ex) {
                completion.completeExceptionally(ex);
                throw ex;
            } catch (Error error) {
                completion.completeExceptionally(error);
                throw error;
            }
        }

        private void stepCompleted(TaskStep step, Throwable stepFailure) {
            if (result.isDone()) {
                return;
            }

            if (stepFailure != null) {
                result.completeExceptionally(stepFailure);
            } else if (step == null) {
                result.completeExceptionally(
                        new IllegalStateException("yielding task returned no TaskStep"));
            } else {
                switch (step.type()) {
                    case DONE:
                        result.complete(null);
                        break;
                    case AGAIN:
                        scheduleStep(0L);
                        break;
                    case AFTER:
                        scheduleAfter(step.delay());
                        break;
                    default:
                        result.completeExceptionally(
                                new IllegalStateException(
                                        "Unsupported yielding task step " + step.type()));
                }
            }
        }

        private void scheduleAfter(Duration delay) {
            if (delay == null) {
                result.completeExceptionally(
                        new IllegalStateException("AFTER task step requires a delay"));
                return;
            }

            final long delayNanos;
            try {
                delayNanos = delay.toNanos();
            } catch (ArithmeticException ex) {
                result.completeExceptionally(
                        new IllegalArgumentException("task delay is too large", ex));
                return;
            }

            scheduleStep(delayNanos <= 0L ? 0L : delayNanos);
        }
    }

    private final long timeoutNanos;

    /** Creates a task lane with an explicit maximum batch size per worker run. */
    public TaskSerialLane(
            int capacity,
            int maxItemsPerRun,
            String laneName,
            ScheduledExecutorService executor) {
        super(capacity, maxItemsPerRun, laneName, executor);
        timeoutNanos = 0L;
    }

    /** Creates a task lane with explicit batch size and timeout for await(). */
    public TaskSerialLane(
            int capacity,
            int maxItemsPerRun,
            String laneName,
            ScheduledExecutorService executor,
            Duration timeout) {
        super(capacity, maxItemsPerRun, laneName, executor);
        timeoutNanos = timeoutNanos(timeout);
    }

    /** Runs one cooperative task until DONE or failure. */
    public CompletableFuture<Void> runTask(YieldingTask task) {
        failIfInvalidArgument(task != null, "task must not be null");
        return new TaskRun(task).start();
    }

    /** Waits for one cooperative task using this lane's configured timeout. */
    public void await(CompletableFuture<Void> future) {
        failIfIllegalState(timeoutNanos > 0L, "This TaskSerialLane has no configured wait timeout");
        failIfInvalidArgument(future != null, "future must not be null");

        try {
            future.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw failure(FailureReason.TIMEOUT, "Serial task timed out", ex);
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(FailureReason.INTERRUPTED, "Serial task wait was interrupted", ex);
        } catch (CancellationException ex) {
            throw failure(
                    FailureReason.OVERLOADED,
                    "Serial task was cancelled before completion",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
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

    private static long timeoutNanos(Duration timeout) {
        failIfInvalidArgument(
                timeout != null && !timeout.isZero() && !timeout.isNegative(),
                "timeout must be positive");
        try {
            return timeout.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("timeout is too large", ex);
        }
    }

    private static OperationException failure(
            FailureReason reason,
            String message,
            Throwable cause) {
        return new OperationException(reason, message, cause);
    }
}
