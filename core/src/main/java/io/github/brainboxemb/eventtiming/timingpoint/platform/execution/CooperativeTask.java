package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

/**
 * One cooperative multi-step operation executed by a {@link CooperativeTaskRunner}.
 *
 * <p>Each invocation performs exactly one logical cooperative step and
 * returns what the runner should do next. A task may implement a multi-phase
 * state machine, but that is not required; the task owns its own operation/control
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
