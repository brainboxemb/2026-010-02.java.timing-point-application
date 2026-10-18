package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;

import java.time.Duration;
import java.util.function.Supplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Executes one result-bearing NodeService read on the node Task Lane. */
final class NodeServiceQuery<T> implements YieldingTask {
    private final Supplier<T> operation;
    private T result;

    private NodeServiceQuery(Supplier<T> operation) {
        failIfInvalidArgument(operation != null, "operation must not be null");
        this.operation = operation;
    }

    /** Runs the read immediately when already on the Task Lane, otherwise queues and waits. */
    static <T> T execute(
            SerialTaskRunner taskRunner,
            Duration timeout,
            Supplier<T> operation) {

        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(timeout != null, "timeout must not be null");
        failIfInvalidArgument(operation != null, "operation must not be null");

        if (taskRunner.isExecutingOnCurrentThread()) {
            return operation.get();
        }

        NodeServiceQuery<T> query = new NodeServiceQuery<T>(operation);
        taskRunner.await(taskRunner.runTask(query), timeout);
        return query.result;
    }

    @Override
    public TaskStep runStep() {
        result = operation.get();
        return TaskStep.done();
    }
}
