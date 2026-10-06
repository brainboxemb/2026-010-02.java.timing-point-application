package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

/**
 * One cooperative multi-step operation executed by {@link ScheduledTaskRunner}.
 *
 * <p>Each invocation performs exactly one logical state-machine step and
 * returns what the runner should do next. The task owns operation-specific
 * state; it does not own an executor or scheduler.</p>
 */
@FunctionalInterface
public interface CooperativeTask {

    /**
     * Executes one logical step.
     *
     * @return continuation decision for this task
     */
    TaskStep runStep();
}
