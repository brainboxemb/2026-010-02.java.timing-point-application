package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the periodic runtime task that expires tag-observation passages.
 *
 * <p>The filter itself has no scheduler lifecycle. This class is the resource
 * that runtime composition starts and closes.</p>
 */
public final class TagProcessingExpiryScheduler implements AutoCloseable {
    private static final Logger LOG =
            LoggerFactory.getLogger(TagProcessingExpiryScheduler.class);

    private final TagProcessor processor;
    private final ScheduledFuture<?> expiryTask;

    public TagProcessingExpiryScheduler(
            TagProcessor processor,
            TagProcessingPolicy policy,
            ScheduledExecutorService scheduler) {
        if (processor == null) {
            throw new IllegalArgumentException("processor must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler must not be null");
        }

        this.processor = processor;

        /*
         * One periodic task per processor keeps scheduler state bounded. We do
         * not create one ScheduledFuture for every RFID observation.
         */
        expiryTask = scheduler.scheduleWithFixedDelay(
                this::expireSafely,
                policy.sweepCadenceNanos(),
                policy.sweepCadenceNanos(),
                TimeUnit.NANOSECONDS);
    }

    private void expireSafely() {
        try {
            processor.expireObservations();
        } catch (RuntimeException ex) {
            // ScheduledExecutorService suppresses later runs if this escapes.
            LOG.warn("Tag processing expiry pass failed", ex);
        }
    }

    @Override
    public void close() {
        expiryTask.cancel(false);
    }
}
