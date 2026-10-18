package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.scheduling;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.RunnableSerialLane;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Adds delayed admission to an existing {@link RunnableSerialLane}.
 *
 * <p>This is a timing helper, not another lane. The lane remains the sole owner
 * of FIFO ordering and serial execution; the JDK scheduled executor only decides when an
 * operation becomes eligible for admission.</p>
 */
public final class LaneScheduler {

    private final RunnableSerialLane lane;
    private final ScheduledExecutorService scheduledExecutor;

    /** Creates delayed admission support for one existing RunnableSerialLane. */
    public LaneScheduler(RunnableSerialLane lane, ScheduledExecutorService scheduledExecutor) {
        failIfInvalidArgument(lane != null, "lane must not be null");
        failIfInvalidArgument(scheduledExecutor != null, "scheduledExecutor must not be null");
        this.lane = lane;
        this.scheduledExecutor = scheduledExecutor;
    }

    /** Schedules one Callable for later admission to the owning serial lane. */
    public <R> CompletableFuture<R> submitAfter(Callable<R> operation, long delayNanos) {
        failIfInvalidArgument(operation != null, "operation must not be null");
        failIfInvalidArgument(delayNanos >= 0L, "delayNanos must not be negative");

        DelayedOperation<R> delayed = new DelayedOperation<R>(operation, delayNanos);
        delayed.schedule();
        return delayed.completion();
    }

    /**
     * Schedules recurring fire-and-forget work with delay measured after each queued operation.
     *
     * <p>The scheduled executor only wakes the operation. The callback itself always runs
     * on the owning serial lane. If that bounded lane is temporarily full, the
     * next normal delay is used rather than creating a second queue.</p>
     */
    public Runnable scheduleWithFixedDelay(Runnable operation, long delayNanos) {
        failIfInvalidArgument(operation != null, "operation must not be null");
        failIfInvalidArgument(delayNanos > 0L, "delayNanos must be positive");

        RepeatingOperation repeating = new RepeatingOperation(operation, delayNanos);
        repeating.scheduleNext();
        return repeating::cancel;
    }

    private final class DelayedOperation<R> {
        private final Callable<R> operation;
        private final long delayNanos;
        private final CompletableFuture<R> completion =
                new CompletableFuture<R>();
        private final AtomicReference<Future<?>> admitted =
                new AtomicReference<Future<?>>();

        private volatile ScheduledFuture<?> trigger;

        /** Creates one delayed one-shot operation and its public completion handles. */
        private DelayedOperation(
                Callable<R> operation,
                long delayNanos) {
            this.operation = operation;
            this.delayNanos = delayNanos;
            completion.whenComplete(this::completionFinished);
        }

        /** Cancels active scheduling when the public completion is cancelled. */
        private void completionFinished(
                R ignoredResult,
                Throwable ignoredFailure) {
            if (completion.isCancelled()) {
                cancelActive();
            }
        }

        /** Schedules the timer trigger that later submits this one-shot operation to the lane. */
        private void schedule() {
            try {
                trigger =
                        scheduledExecutor.schedule(
                                this::admit,
                                delayNanos,
                                TimeUnit.NANOSECONDS);
            } catch (RejectedExecutionException ex) {
                completion.completeExceptionally(ex);
            }
        }

        /** Submits the triggered one-shot operation to the owning serial lane. */
        private void admit() {
            trigger = null;
            if (completion.isDone()) {
                return;
            }

            final Future<R> future;
            try {
                future = lane.submit(this::execute);
            } catch (RejectedExecutionException ex) {
                completion.completeExceptionally(ex);
                return;
            }

            admitted.set(future);
            if (completion.isCancelled()) {
                future.cancel(true);
            }
        }

        /** Executes the one-shot callable on the serial lane and completes its public result. */
        private R execute()
                throws Exception {
            try {
                R result =
                        operation.call();
                completion.complete(result);
                return result;
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

        /** Returns the one public result/cancellation handle for this delayed operation. */
        private CompletableFuture<R> completion() {
            return completion;
        }

        /** Cancels both the timer trigger and any lane Future already admitted for this operation. */
        private void cancelActive() {
            ScheduledFuture<?> currentTrigger =
                    trigger;
            if (currentTrigger != null) {
                currentTrigger.cancel(true);
            }

            Future<?> current =
                    admitted.get();
            if (current != null) {
                current.cancel(true);
            }
        }
    }

    private final class RepeatingOperation {
        private final Runnable operation;
        private final long delayNanos;

        private boolean closed;
        private ScheduledFuture<?> trigger;

        /** Creates one fixed-delay registration around the supplied operation. */
        private RepeatingOperation(
                Runnable operation,
                long delayNanos) {
            this.operation = operation;
            this.delayNanos = delayNanos;
        }

        /** Schedules the next fixed-delay trigger unless this registration is closed. */
        private void scheduleNext() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                try {
                    trigger =
                            scheduledExecutor.schedule(
                                    this::admit,
                                    delayNanos,
                                    TimeUnit.NANOSECONDS);
                } catch (RejectedExecutionException ex) {
                    closed = true;
                }
            }
        }

        /** Submits one fixed-delay turn to the owning serial lane. */
        private void admit() {
            synchronized (this) {
                trigger = null;
                if (closed) {
                    return;
                }
            }

            try {
                lane.execute(this::runAndScheduleNext);
            } catch (RejectedExecutionException ex) {
                scheduleNext();
            }
        }

        /** Runs one admitted fixed-delay operation and schedules its next trigger afterwards. */
        private void runAndScheduleNext() {
            try {
                operation.run();
            } finally {
                scheduleNext();
            }
        }

        /** Stops future fixed-delay triggers without interrupting unrelated lane work. */
        private void cancel() {
            ScheduledFuture<?> pending;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                pending = trigger;
                trigger = null;
            }
            if (pending != null) {
                pending.cancel(false);
            }
        }
    }
}
