package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Executes one fire-and-forget NodeService state update on the node Task Lane. */
final class NodeServiceUpdateTask extends AbstractTask {
    private final Runnable operation;
    private final Consumer<Throwable> failureHandler;

    private NodeServiceUpdateTask(
            Runnable operation,
            Consumer<Throwable> failureHandler) {

        failIfInvalidArgument(operation != null, "operation must not be null");
        failIfInvalidArgument(failureHandler != null, "failureHandler must not be null");
        this.operation = operation;
        this.failureHandler = failureHandler;
    }

    /** Runs immediately on the Task Lane or queues one independent update task. */
    static void execute(
            SerialTaskRunner taskRunner,
            Runnable operation,
            Consumer<Throwable> failureHandler) {

        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");

        if (taskRunner.isExecutingOnCurrentThread()) {
            operation.run();
        } else {
            new NodeServiceUpdateTask(operation, failureHandler).wake(taskRunner);
        }
    }

    @Override
    protected void resetForRun() {
        // One-shot task has no reusable state.
    }

    @Override
    public TaskStep runStep() {
        operation.run();
        return TaskStep.done();
    }

    @Override
    protected void onRunCompleted(Throwable failure) {
        if (failure != null) {
            failureHandler.accept(failure);
        }
    }
}
