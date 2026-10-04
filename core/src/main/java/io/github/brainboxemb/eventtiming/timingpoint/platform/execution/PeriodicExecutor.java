package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

/** Registers short periodic operations. */
public interface PeriodicExecutor {

    /**
     * Registers one fixed-delay operation.
     *
     * @param task operation to invoke
     * @param delayNanos delay between the end of one invocation and the start
     *                   of the next
     * @return handle owned by the component that registered the operation
     */
    PeriodicTask scheduleWithFixedDelay(Runnable task, long delayNanos);
}
