package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.scheduling;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adds delayed admission to an existing {@link SerialLane}.
 *
 * <p>This is a timing helper, not another lane. The lane remains the sole owner
 * of FIFO ordering and serial execution; the JDK scheduler only decides when an
 * operation becomes eligible for admission.</p>
 */
public final class LaneScheduler {

    public interface ScheduledRegistration extends AutoCloseable {
        @Override
        void close();
    }

    public interface ScheduledOperation<R> extends ScheduledRegistration {
        Future<R> futureResult();

        CompletionStage<R> completion();

        @Override
        void close();
    }

    private final SerialLane lane;
    private final ScheduledExecutorService scheduler;

    /** Creates delayed admission support for one existing SerialLane. */
    public LaneScheduler(
            SerialLane lane,
            ScheduledExecutorService scheduler) {
        if (lane == null) {
            throw new IllegalArgumentException("lane must not be null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler must not be null");
        }
        this.lane = lane;
        this.scheduler = scheduler;
    }

    /** Schedules one Callable for later admission to the owning serial lane. */
    public <R> ScheduledOperation<R> submitAfter(
            Callable<R> operation,
            long delayNanos) {
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        if (delayNanos < 0L) {
            throw new IllegalArgumentException("delayNanos must not be negative");
        }

        DelayedOperation<R> delayed =
                new DelayedOperation<R>(
                        operation,
                        delayNanos);
        delayed.schedule();
        return delayed;
    }

    /**
     * Repeats an operation with a fixed delay after each completed lane turn.
     *
     * <p>The scheduler only wakes the operation. The callback itself always runs
     * on the owning serial lane. If that bounded lane is temporarily full, the
     * next normal delay is used rather than creating a second queue.</p>
     */
    /** Schedules recurring fire-and-forget work with delay measured after each lane turn. */
    public ScheduledRegistration scheduleWithFixedDelay(
            Runnable operation,
            long delayNanos) {
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        if (delayNanos <= 0L) {
            throw new IllegalArgumentException("delayNanos must be positive");
        }

        RepeatingOperation repeating =
                new RepeatingOperation(
                        operation,
                        delayNanos);
        repeating.scheduleNext();
        return repeating;
    }

    private final class DelayedOperation<R>
            implements ScheduledOperation<R> {
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
            completion.whenComplete(
                    (ignored, failure) -> {
                        if (completion.isCancelled()) {
                            cancelActive();
                        }
                    });
        }

        /** Schedules the timer trigger that later submits this one-shot operation to the lane. */
        private void schedule() {
            try {
                trigger =
                        scheduler.schedule(
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

        @Override
        /** Returns the Future representing this delayed operation's final result. */
        public Future<R> futureResult() {
            return completion;
        }

        @Override
        /** Returns the completion-stage view used by asynchronous consumers. */
        public CompletionStage<R> completion() {
            return completion;
        }

        @Override
        /** Cancels this delayed operation and any trigger or lane Future still active. */
        public void close() {
            completion.cancel(true);
            cancelActive();
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

    private final class RepeatingOperation
            implements ScheduledRegistration {
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
                            scheduler.schedule(
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
                lane.execute(
                        () -> {
                            try {
                                operation.run();
                            } finally {
                                scheduleNext();
                            }
                        });
            } catch (RejectedExecutionException ex) {
                scheduleNext();
            }
        }

        /** Stops future fixed-delay triggers without interrupting unrelated lane work. */
        @Override
        public void close() {
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
