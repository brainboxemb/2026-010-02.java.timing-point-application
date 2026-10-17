package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.scheduling.LaneScheduler;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * SerialLane with delayed and recurring admission support.
 *
 * <p>The supplied ScheduledExecutorService is both the backing executor for
 * ordinary lane turns and the timer source for delayed admission. Scheduled
 * callbacks are always admitted back to this lane before application work runs.</p>
 */
public class ScheduledSerialLane extends SerialLane {

    /** Handle for one recurring or delayed scheduling registration. */
    public interface ScheduledRegistration extends AutoCloseable {
        @Override
        void close();
    }

    /** Result handle for one delayed result-bearing lane operation. */
    public interface ScheduledOperation<R> extends ScheduledRegistration {
        Future<R> futureResult();

        CompletionStage<R> completion();
    }

    private final LaneScheduler scheduler;

    public ScheduledSerialLane(
            int capacity,
            String laneName,
            ScheduledExecutorService executor) {

        super(capacity, laneName, executor);
        failIfInvalidArgument(executor != null, "executor must not be null");
        scheduler = new LaneScheduler(this, executor);
    }

    /** Schedules one operation for later admission to this serial lane. */
    public <R> ScheduledOperation<R> submitAfter(
            Callable<R> operation,
            long delayNanos) {

        final LaneScheduler.ScheduledOperation<R> scheduled =
                scheduler.submitAfter(operation, delayNanos);

        return new ScheduledOperation<R>() {
            @Override
            public Future<R> futureResult() {
                return scheduled.futureResult();
            }

            @Override
            public CompletionStage<R> completion() {
                return scheduled.completion();
            }

            @Override
            public void close() {
                scheduled.close();
            }
        };
    }

    /** Schedules recurring work with delay measured after each completed lane turn. */
    public ScheduledRegistration scheduleWithFixedDelay(
            Runnable operation,
            long delayNanos) {

        final LaneScheduler.ScheduledRegistration scheduled =
                scheduler.scheduleWithFixedDelay(operation, delayNanos);
        return scheduled::close;
    }
}
