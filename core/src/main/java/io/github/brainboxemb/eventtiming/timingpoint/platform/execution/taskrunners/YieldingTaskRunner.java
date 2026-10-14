package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.taskrunners;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.util.concurrent.CompletableFuture;

/**
 * Execution boundary for one yielding task run.
 *
 * <p>The task owns its operation/control state. A runner owns only how successive
 * {@link YieldingTask#runStep()} turns are admitted to an execution lane.</p>
 */
@FunctionalInterface
public interface YieldingTaskRunner {

    /**
     * Runs the supplied task until it returns {@link TaskStep#done()} or fails.
     *
     * @return completion of this task run
     */
    CompletableFuture<Void> runTask(YieldingTask task);
}
