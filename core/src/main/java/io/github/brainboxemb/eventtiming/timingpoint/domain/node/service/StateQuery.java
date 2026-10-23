package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Result-bearing NodeState read task.
 *
 * <p>The task may first wait for projection catch-up without blocking the Task Lane worker.
 * The supplied read itself always runs on that lane.</p>
 */
final class StateQuery<T> implements YieldingTask {
    private final CompletionStage<?> catchUpCompletion;
    private final Supplier<T> operation;
    private T result;

    /** Creates one read task with an optional completion that must settle before the read runs. */
    private StateQuery(
            CompletionStage<?> catchUpCompletion,
            Supplier<T> operation) {

        failIfInvalidArgument(operation != null, "operation must not be null");
        this.catchUpCompletion = catchUpCompletion;
        this.operation = operation;
    }

    /**
     * Runs directly when already on the Task Lane; otherwise blocks only the caller for the result.
     */
    static <T> T execute(
            SerialTaskRunner taskRunner,
            Duration timeout,
            CompletionStage<?> catchUpCompletion,
            Supplier<T> operation) {

        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(timeout != null, "timeout must not be null");
        failIfInvalidArgument(operation != null, "operation must not be null");

        if (taskRunner.isExecutingOnCurrentThread()) {
            return operation.get();
        }

        StateQuery<T> query = new StateQuery<T>(catchUpCompletion, operation);
        taskRunner.await(taskRunner.runTask(query), timeout);
        return query.result;
    }

    /** Yields until catch-up completes, then performs the read on the Task Lane. */
    @Override
    public TaskStep runStep() {

        if (catchUpCompletion != null) {
            if (!catchUpCompletion.toCompletableFuture().isDone()) {
                return TaskStep.await(catchUpCompletion);
            }
            catchUpCompletion.toCompletableFuture().join();
        }

        result = operation.get();
        return TaskStep.done();
    }
}
