package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Executes one result-bearing NodeService read on the node Task Lane. */
final class NodeServiceQuery<T> implements YieldingTask {
    private final CompletionStage<?> dependency;
    private final Supplier<T> operation;
    private T result;

    private NodeServiceQuery(
            CompletionStage<?> dependency,
            Supplier<T> operation) {

        failIfInvalidArgument(operation != null, "operation must not be null");
        this.dependency = dependency;
        this.operation = operation;
    }

    /** Runs one ordered read, yielding first while the optional dependency is incomplete. */
    static <T> T execute(
            SerialTaskRunner taskRunner,
            Duration timeout,
            CompletionStage<?> dependency,
            Supplier<T> operation) {

        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(timeout != null, "timeout must not be null");
        failIfInvalidArgument(operation != null, "operation must not be null");

        if (taskRunner.isExecutingOnCurrentThread()) {
            return operation.get();
        }

        NodeServiceQuery<T> query =
                new NodeServiceQuery<T>(
                        dependency,
                        operation);
        taskRunner.await(
                taskRunner.runTask(query),
                timeout);
        return query.result;
    }

    @Override
    public TaskStep runStep() {
        if (dependency != null) {
            if (!dependency.toCompletableFuture().isDone()) {
                return TaskStep.await(dependency);
            }
            dependency.toCompletableFuture().join();
        }

        result = operation.get();
        return TaskStep.done();
    }
}
