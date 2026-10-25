package oss.relay.timing.timingpoint.platform.execution.tasks;


/**
 * One cooperatively scheduled operation or control task executed by a
 * {@link SerialTaskRunner}.
 *
 * <p>Each invocation performs exactly one logical cooperative step and
 * returns what the task lane should do next. A task may implement a multi-phase
 * state machine, but that is not required. The task owns its operation/control
 * state; it does not own an executor or scheduler.</p>
 */
@FunctionalInterface
public interface YieldingTask {

    /**
     * Executes one logical step.
     *
     * @return continuation decision for this task
     */
    TaskStep runStep();
}
