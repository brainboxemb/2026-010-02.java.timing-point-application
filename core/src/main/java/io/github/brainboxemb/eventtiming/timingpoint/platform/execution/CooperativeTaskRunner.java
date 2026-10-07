package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.CompletableFuture;

/**
 * Execution boundary for one cooperative task run.
 *
 * <p>The task owns its operation/control state. A runner owns only how successive
 * {@link CooperativeTask#runStep()} turns are admitted to an execution lane.</p>
 */
@FunctionalInterface
public interface CooperativeTaskRunner {

    /**
     * Runs the supplied task until it returns {@link TaskStep#done()} or fails.
     *
     * @return completion of this task run
     */
    CompletableFuture<Void> runTask(CooperativeTask task);
}
