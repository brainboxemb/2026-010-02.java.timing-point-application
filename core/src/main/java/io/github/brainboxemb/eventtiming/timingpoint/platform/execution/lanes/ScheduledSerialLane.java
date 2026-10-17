package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.scheduling.LaneScheduler;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * RunnableSerialLane with delayed and recurring admission support.
 *
 * <p>The supplied ScheduledExecutorService is both the backing executor for
 * ordinary queue processing and the timer source for delayed admission. Scheduled
 * callbacks are always admitted back to this lane before application work runs.</p>
 */
public class ScheduledSerialLane extends RunnableSerialLane {

    /** Handle for one recurring scheduling registration. */
    public interface ScheduledRegistration extends AutoCloseable {
        @Override
        void close();
    }

    private final LaneScheduler scheduler;

    /** Creates a scheduled lane with an explicit maximum batch size per worker run. */
    public ScheduledSerialLane(
            int capacity,
            int maxItemsPerRun,
            String laneName,
            ScheduledExecutorService executor) {

        super(capacity, maxItemsPerRun, laneName, executor);
        failIfInvalidArgument(executor != null, "executor must not be null");
        scheduler = new LaneScheduler(this, executor);
    }

    /** Schedules one operation for later admission to this serial lane. */
    public <R> CompletableFuture<R> submitAfter(
            Callable<R> operation,
            long delayNanos) {

        return scheduler.submitAfter(operation, delayNanos);
    }

    /** Schedules recurring work with delay measured after each completed queued operation. */
    public ScheduledRegistration scheduleWithFixedDelay(
            Runnable operation,
            long delayNanos) {

        Runnable cancel =
                scheduler.scheduleWithFixedDelay(operation, delayNanos);
        return cancel::run;
    }
}
