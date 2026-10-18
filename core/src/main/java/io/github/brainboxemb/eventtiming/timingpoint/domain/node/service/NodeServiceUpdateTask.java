package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Executes one fire-and-forget NodeService state update on the Task Lane. */
final class NodeServiceUpdateTask extends AbstractTask {
    private final Runnable operation;
    private final Consumer<Throwable> failureHandler;

    private NodeServiceUpdateTask(Runnable operation, Consumer<Throwable> failureHandler) {

        failIfInvalidArgument(operation != null, "operation must not be null");
        failIfInvalidArgument(failureHandler != null, "failureHandler must not be null");

        this.operation = operation;
        this.failureHandler = failureHandler;
    }

    /** Queues one ordered state update and returns after admission. */
    static void queue(
            SerialTaskRunner taskRunner,
            Runnable operation,
            Consumer<Throwable> failureHandler) {

        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");

        new NodeServiceUpdateTask(operation, failureHandler).wake(taskRunner);
    }

    /** Resets one-shot task state before admission. */
    @Override
    protected void resetForRun() {

        // One-shot task has no reusable state.
    }

    /** Executes the queued state update. */
    @Override
    public TaskStep runStep() {

        operation.run();
        return TaskStep.done();
    }

    /** Delegates asynchronous update failure to NodeService. */
    @Override
    protected void onRunCompleted(Throwable failure) {

        if (failure != null) {
            failureHandler.accept(failure);
        }
    }
}
