package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * PeriodicExecutor backed by a supplied ScheduledExecutorService.
 *
 * <p>The supplied scheduler may be shared. This adapter never closes it; the
 * application-level owner controls the executor lifecycle.</p>
 */
public final class ScheduledPeriodicExecutor implements PeriodicExecutor {
    private final ScheduledExecutorService scheduler;

    public ScheduledPeriodicExecutor(ScheduledExecutorService scheduler) {
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler must not be null");
        }
        this.scheduler = scheduler;
    }

    @Override
    public PeriodicTask scheduleWithFixedDelay(
            Runnable task,
            long delayNanos) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (delayNanos < 1L) {
            throw new IllegalArgumentException("delayNanos must be positive");
        }

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                task,
                delayNanos,
                delayNanos,
                TimeUnit.NANOSECONDS);

        return () -> future.cancel(false);
    }
}
