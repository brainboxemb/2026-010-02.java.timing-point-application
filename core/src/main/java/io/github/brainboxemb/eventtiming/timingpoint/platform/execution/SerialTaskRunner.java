package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

/**
 * Runs cooperative tasks on an existing {@link SerialExecutor}.
 *
 * <p>This runner is for short application/domain control steps that need serial
 * ordering and cooperative yielding but no elapsed-time scheduling. Each
 * {@link TaskStep#again()} is admitted at the back of the same lane, so sibling
 * work already waiting on that lane gets a turn first.</p>
 *
 * <p>{@link TaskStep#after(java.time.Duration)} requires a scheduler and is
 * therefore supported only by {@link ScheduledTaskRunner}. This runner owns no
 * lane lifecycle; its composition owner starts and closes the supplied lane.</p>
 */
public final class SerialTaskRunner implements CooperativeTaskRunner {
    private final SerialExecutor lane;

    public SerialTaskRunner(
            SerialExecutor lane) {
        if (lane == null) {
            throw new IllegalArgumentException(
                    "lane must not be null");
        }
        this.lane = lane;
    }

    @Override
    public CompletableFuture<Void> runTask(
            CooperativeTask task) {
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }

        CompletableFuture<Void> result =
                new CompletableFuture<Void>();
        scheduleStep(
                task,
                result);
        return result;
    }

    private void scheduleStep(
            CooperativeTask task,
            CompletableFuture<Void> result) {
        if (result.isDone()) {
            return;
        }

        SerialExecutor.AdmissionResult admission =
                lane.offer(
                        () -> executeStep(
                                task,
                                result));

        if (admission != SerialExecutor.AdmissionResult.ACCEPTED) {
            result.completeExceptionally(
                    new RejectedExecutionException(
                            "Serial task lane rejected cooperative task step: "
                                    + admission));
        }
    }

    private void executeStep(
            CooperativeTask task,
            CompletableFuture<Void> result) {
        if (result.isDone()) {
            return;
        }

        final TaskStep step;
        try {
            step = task.runStep();
        } catch (RuntimeException ex) {
            result.completeExceptionally(
                    ex);
            return;
        } catch (Error error) {
            result.completeExceptionally(
                    error);
            throw error;
        }

        if (step == null) {
            result.completeExceptionally(
                    new IllegalStateException(
                            "cooperative task returned no TaskStep"));
            return;
        }

        switch (step.type()) {
            case DONE:
                result.complete(
                        null);
                return;

            case AGAIN:
                scheduleStep(
                        task,
                        result);
                return;

            case AFTER:
                result.completeExceptionally(
                        new IllegalStateException(
                                "TaskStep.AFTER requires ScheduledTaskRunner"));
                return;

            default:
                result.completeExceptionally(
                        new IllegalStateException(
                                "Unsupported cooperative task step "
                                        + step.type()));
        }
    }
}
