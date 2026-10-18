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
    private final CompletionStage<?> recoveryCompletion;
    private final Supplier<T> operation;
    private T result;

    private NodeServiceQuery(CompletionStage<?> recoveryCompletion, Supplier<T> operation) {

        failIfInvalidArgument(operation != null, "operation must not be null");

        this.recoveryCompletion = recoveryCompletion;
        this.operation = operation;
    }

    /** Queues an ordered read and blocks the caller until the Task Lane returns its result. */
    static <T> T execute(
            SerialTaskRunner taskRunner,
            Duration timeout,
            CompletionStage<?> recoveryCompletion,
            Supplier<T> operation) {

        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(timeout != null, "timeout must not be null");
        failIfInvalidArgument(operation != null, "operation must not be null");

        if (taskRunner.isExecutingOnCurrentThread()) {
            return operation.get();
        }

        NodeServiceQuery<T> query = new NodeServiceQuery<T>(recoveryCompletion, operation);
        taskRunner.await(taskRunner.runTask(query), timeout);
        return query.result;
    }

    /** Waits cooperatively for recovery and then executes the requested read. */
    @Override
    public TaskStep runStep() {

        if (recoveryCompletion != null) {
            if (!recoveryCompletion.toCompletableFuture().isDone()) {
                return TaskStep.await(recoveryCompletion);
            }
            recoveryCompletion.toCompletableFuture().join();
        }

        result = operation.get();
        return TaskStep.done();
    }
}
