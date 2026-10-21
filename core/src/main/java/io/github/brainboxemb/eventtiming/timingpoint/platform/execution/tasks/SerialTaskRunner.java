package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLaneMetrics;

import java.time.Duration;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * Serial runner for cooperative {@link YieldingTask} instances.
 *
 * <p>Each eligible task step is submitted directly to the supplied single-thread
 * JDK scheduler. AGAIN resubmits a step, AFTER schedules the next admission,
 * and AWAIT resumes after its dependency completes, without blocking a worker.</p>
 *
 * <p>The runner owns no Java thread. The supplied scheduler/executor remains
 * Runtime-owned and may also back other logical lanes.</p>
 */
public final class SerialTaskRunner implements AutoCloseable {

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

    /** One active or waiting run of a caller-owned cooperative task. */
    private final class TaskRun {
        private final YieldingTask task;
        private final CompletableFuture<Void> result =
                new CompletableFuture<Void>();
        private final AtomicReference<Thread> runningThread =
                new AtomicReference<Thread>();
        private final AtomicBoolean cancellationInterruptRequested =
                new AtomicBoolean();

        private volatile ScheduledFuture<?> delayedAdmission;

        private TaskRun(YieldingTask task) {
            this.task = task;
            result.whenComplete(this::resultCompleted);
        }

        private CompletableFuture<Void> start() {
            synchronized (activeRuns) {
                activeRuns.add(this);
            }

            try {
                offerOrReject();
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
                throw ex;
            }
            return result;
        }

        /** Executes exactly one task step for this queued task run. */
        private void runStep() {
            if (result.isDone()) {
                return;
            }

            Thread current = Thread.currentThread();
            runningThread.set(current);

            final TaskStep step;
            try {
                step = task.runStep();
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
                return;
            } catch (Error error) {
                result.completeExceptionally(error);
                throw error;
            } finally {
                runningThread.compareAndSet(current, null);
                if (cancellationInterruptRequested.getAndSet(false)) {
                    Thread.interrupted();
                }
            }

            if (result.isDone()) {
                return;
            }
            if (step == null) {
                result.completeExceptionally(
                        new IllegalStateException(
                                "yielding task returned no TaskStep"));
                return;
            }

            switch (step.type()) {
                case DONE:
                    result.complete(null);
                    break;
                case AGAIN:
                    admit();
                    break;
                case AFTER:
                    scheduleAfter(step.delay());
                    break;
                case AWAIT:
                    awaitCompletion(step.completion());
                    break;
                default:
                    result.completeExceptionally(
                            new IllegalStateException(
                                    "Unsupported yielding task step "
                                            + step.type()));
            }
        }

        /** Re-admits this task run at the back of the bounded task queue. */
        private void admit() {
            if (result.isDone()) {
                return;
            }
            try {
                offerOrReject();
            } catch (RuntimeException ex) {
                result.completeExceptionally(ex);
            }
        }

        private void offerOrReject() {
            queueStep(this);
        }

        /** Releases the worker and re-admits this task only after the delay. */
        private void scheduleAfter(Duration delay) {
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

            if (delayNanos <= 0L) {
                admit();
                return;
            }

            try {
                delayedAdmission =
                        scheduler.schedule(
                                this::delayedAdmissionReady,
                                delayNanos,
                                TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException ex) {
                result.completeExceptionally(ex);
            }
        }

        private void delayedAdmissionReady() {
            delayedAdmission = null;
            admit();
        }

        /** Releases the worker until the asynchronous dependency completes. */
        private void awaitCompletion(
                java.util.concurrent.CompletionStage<?> completion) {

            if (completion == null) {
                result.completeExceptionally(
                        new IllegalStateException(
                                "AWAIT task step requires a completion"));
                return;
            }

            completion.whenComplete(
                    (ignoredResult, ignoredFailure) -> admit());
        }

        /** Cancels pending work when the public task-run future is cancelled. */
        private void resultCompleted(
                Void ignored,
                Throwable ignoredFailure) {

            if (result.isCancelled()) {
                ScheduledFuture<?> delayed = delayedAdmission;
                if (delayed != null) {
                    delayed.cancel(true);
                }

                Thread current = runningThread.get();
                if (current != null) {
                    cancellationInterruptRequested.set(true);
                    current.interrupt();
                }
            }

            synchronized (activeRuns) {
                activeRuns.remove(this);
            }
        }
    }

    private final ScheduledExecutorService scheduler;
    private final int capacity;
    private final AtomicInteger pendingSteps = new AtomicInteger();
    private final SerialLaneMetrics metrics =
            new SerialLaneMetrics(pendingSteps::get);
    private volatile boolean running;
    private volatile Thread executingThread;
    private final Set<TaskRun> activeRuns =
            new HashSet<TaskRun>();
    private final long timeoutNanos;

    /** Creates a runner without a configured synchronous wait timeout. */
    public SerialTaskRunner(
            int capacity,
            int maxItemsPerRun,
            String name,
            ScheduledExecutorService executor) {

        this(
                capacity,
                maxItemsPerRun,
                name,
                executor,
                0L);
    }

    /** Creates a runner with a default timeout for {@link #await}. */
    public SerialTaskRunner(
            int capacity,
            int maxItemsPerRun,
            String name,
            ScheduledExecutorService executor,
            Duration timeout) {

        this(
                capacity,
                maxItemsPerRun,
                name,
                executor,
                timeoutNanos(timeout));
    }

    private SerialTaskRunner(
            int capacity,
            int maxItemsPerRun,
            String name,
            ScheduledExecutorService executor,
            long timeoutNanos) {

        failIfInvalidArgument(executor != null, "executor must not be null");
        scheduler = executor;
        failIfInvalidArgument(capacity > 0, "capacity must be positive");
        failIfInvalidArgument(maxItemsPerRun > 0, "maxItemsPerRun must be positive");
        failIfInvalidArgument(name != null && !name.trim().isEmpty(),
                "name must not be blank");
        this.capacity = capacity;
        this.timeoutNanos = timeoutNanos;
    }

    /** Starts accepting cooperative task runs. */
    public void start() {
        synchronized (this) {
            failIfIllegalState(!running, "SerialTaskRunner is already running");
            running = true;
        }
    }

    /** Returns whether this runner currently accepts new task runs. */
    public boolean isRunning() {
        return running;
    }

    /** True only while this Java thread executes one task step. */
    public boolean isExecutingOnCurrentThread() {
        return executingThread == Thread.currentThread();
    }

    /** Returns metrics for the queued task runs. */
    public SerialLaneMetrics metrics() {
        return metrics;
    }

    /** Queues one cooperative task run. Initial admission failure is immediate. */
    public CompletableFuture<Void> runTask(YieldingTask task) {
        failIfInvalidArgument(task != null, "task must not be null");
        return new TaskRun(task).start();
    }

    /**
     * Enqueues one eligible task step directly on the JDK executor.
     * AFTER/AWAIT tasks have no queued slot until their continuation is ready.
     */
    private synchronized void queueStep(TaskRun run) {
        if (!running) {
            metrics.recordNotRunning();
            throw new RejectedExecutionException("Serial task runner is not running");
        }
        if (pendingSteps.get() >= capacity) {
            metrics.recordFull();
            throw new RejectedExecutionException("Serial task queue is full");
        }

        long queuedAt = System.nanoTime();
        int depth = pendingSteps.incrementAndGet();
        try {
            scheduler.execute(() -> executeStep(run, queuedAt));
            metrics.recordQueued(depth);
        } catch (RejectedExecutionException ex) {
            pendingSteps.decrementAndGet();
            throw ex;
        }
    }

    /** Executes one step; a yielding continuation becomes a new executor task. */
    private void executeStep(TaskRun run, long queuedAt) {
        long started = System.nanoTime();
        pendingSteps.decrementAndGet();
        executingThread = Thread.currentThread();
        try {
            run.runStep();
        } finally {
            executingThread = null;
            long finished = System.nanoTime();
            metrics.recordCompleted(
                    Math.max(0L, started - queuedAt),
                    Math.max(0L, finished - started));
        }
    }

    /** Waits for one cooperative task using this runner's configured timeout. */
    public void await(CompletableFuture<Void> future) {
        failIfIllegalState(
                timeoutNanos > 0L,
                "This SerialTaskRunner has no configured wait timeout");
        await(future, timeoutNanos);
    }

    /** Waits for one cooperative task using a caller-owned timeout policy. */
    public void await(
            CompletableFuture<Void> future,
            Duration timeout) {

        failIfInvalidArgument(
                timeout != null
                        && !timeout.isZero()
                        && !timeout.isNegative(),
                "timeout must be positive");
        await(future, timeoutNanos(timeout));
    }

    private void await(
            CompletableFuture<Void> future,
            long waitTimeoutNanos) {

        failIfInvalidArgument(future != null, "future must not be null");

        try {
            future.get(waitTimeoutNanos, TimeUnit.NANOSECONDS);
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

    /** Cancels task runs and stops admission without waiting for a running task step. */
    @Override
    public void close() {
        synchronized (this) {
            running = false;
        }
        TaskRun[] runs;
        synchronized (activeRuns) {
            runs =
                    activeRuns.toArray(
                            new TaskRun[activeRuns.size()]);
        }

        for (TaskRun run : runs) {
            run.result.cancel(true);
        }

    }

    private static long timeoutNanos(Duration timeout) {
        failIfInvalidArgument(
                timeout != null
                        && !timeout.isZero()
                        && !timeout.isNegative(),
                "timeout must be positive");
        try {
            return timeout.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(
                    "timeout is too large",
                    ex);
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
