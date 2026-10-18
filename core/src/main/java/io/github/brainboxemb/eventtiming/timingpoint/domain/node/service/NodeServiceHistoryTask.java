package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Keeps NodeService derived state caught up with the LogBook source head.
 *
 * <p>The LogBook publishes only its committed head. This task chooses its own
 * bounded read size and yields while each ordered LogBook range query runs.</p>
 */
final class NodeServiceHistoryTask extends AbstractTask {
    private static final int TIMING_DATA_BATCH_SIZE = 256;

    private final LogBookAccessor logBook;
    private final NodeServiceState state;
    private final Consumer<Throwable> failureHandler;

    private volatile long targetHead = -1L;
    private long nextSequence = 1L;
    private boolean initialized;
    private CompletableFuture<List<TimingData>> pendingRead;

    NodeServiceHistoryTask(
            LogBookAccessor logBook,
            NodeServiceState state,
            Consumer<Throwable> failureHandler) {

        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(state != null, "state must not be null");
        failIfInvalidArgument(failureHandler != null, "failureHandler must not be null");

        this.logBook = logBook;
        this.state = state;
        this.failureHandler = failureHandler;
    }

    /** Updates the desired LogBook head and wakes the catch-up task. */
    void headChanged(long head, SerialTaskRunner taskRunner) {
        failIfInvalidArgument(head >= 0L, "head must not be negative");
        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");

        if (head > targetHead) {
            targetHead = head;
        }
        wake(taskRunner);
    }

    /** Cancels current work and resets history state for a later activation. */
    void stop() {
        cancel();
        targetHead = -1L;
        nextSequence = 1L;
        initialized = false;
        pendingRead = null;
    }

    @Override
    protected void resetForRun() {
        pendingRead = null;
    }

    @Override
    public TaskStep runStep() {
        if (!initialized) {
            state.beginDerivedTimingRecovery();
            initialized = true;
        }

        if (pendingRead != null) {
            applyCompletedRead();
        }

        long head = targetHead;
        if (nextSequence > head) {
            state.completeDerivedTimingRecovery();
            return TaskStep.done();
        }

        int limit =
                (int) Math.min(
                        (long) TIMING_DATA_BATCH_SIZE,
                        head - nextSequence + 1L);
        pendingRead =
                logBook.submit(
                        LogBookQueries.timingDataRange(
                                nextSequence,
                                limit));
        return TaskStep.await(pendingRead);
    }

    @Override
    protected void onRunCompleted(Throwable failure) {
        pendingRead = null;
        if (failure != null) {
            state.markDerivedTimingStale();
            failureHandler.accept(failure);
        }
    }

    /** Applies one completed batch in strict source-sequence order. */
    private void applyCompletedRead() {
        List<TimingData> records = pendingRead.join();
        pendingRead = null;

        failIfIllegalState(
                !records.isEmpty(),
                "LogBook head %s advertised sequence %s but returned no TimingData",
                targetHead,
                nextSequence);

        for (TimingData data : records) {
            failIfIllegalState(
                    data.sequenceNumber() == nextSequence,
                    "NodeService expected TimingData sequence %s but got %s",
                    nextSequence,
                    data.sequenceNumber());
            state.applyTimingData(data);
            nextSequence++;
        }
    }
}
