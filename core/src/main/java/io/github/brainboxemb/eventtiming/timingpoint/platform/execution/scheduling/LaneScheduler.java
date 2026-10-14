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

    public interface ScheduledOperation<R> extends AutoCloseable {
        Future<R> futureResult();

        CompletionStage<R> completion();

        @Override
        void close();
    }

    private final SerialLane lane;
    private final ScheduledExecutorService scheduler;

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

    private final class DelayedOperation<R>
            implements ScheduledOperation<R> {
        private final Callable<R> operation;
        private final long delayNanos;
        private final CompletableFuture<R> completion =
                new CompletableFuture<R>();
        private final AtomicReference<Future<?>> admitted =
                new AtomicReference<Future<?>>();

        private volatile ScheduledFuture<?> trigger;

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

        private void admit() {
            trigger = null;
            if (completion.isDone()) {
                return;
            }

            SerialLane.SubmitResult<R> submitted =
                    lane.submit(
                            this::execute);

            if (submitted.admission()
                    != SerialLane.AdmissionResult.ACCEPTED) {
                completion.completeExceptionally(
                        new RejectedExecutionException(
                                "Serial lane rejected scheduled operation: "
                                        + submitted.admission()));
                return;
            }

            Future<R> future =
                    submitted.futureResult();
            admitted.set(future);
            if (completion.isCancelled()) {
                future.cancel(true);
            }
        }

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
        public Future<R> futureResult() {
            return completion;
        }

        @Override
        public CompletionStage<R> completion() {
            return completion;
        }

        @Override
        public void close() {
            completion.cancel(true);
            cancelActive();
        }

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
}
