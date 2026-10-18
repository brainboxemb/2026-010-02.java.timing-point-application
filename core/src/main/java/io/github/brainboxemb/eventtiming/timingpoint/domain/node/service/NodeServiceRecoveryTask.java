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
 * Rebuilds NodeService derived state from recovered LogBook TimingData.
 *
 * <p>The recovery event carries only the recovered source head. This task chooses its own
 * bounded range size and yields while each ordered LogBook query runs. Commits arriving
 * during recovery extend the target head so no record can overtake reconstruction.</p>
 */
final class NodeServiceRecoveryTask extends AbstractTask {
    private static final int TIMING_DATA_BATCH_SIZE = 64;

    private final LogBookAccessor logBook;
    private final NodeServiceState state;
    private final Consumer<Throwable> failureHandler;

    private volatile boolean recovering;
    private volatile long targetHead = -1L;
    private long nextSequence = 1L;
    private boolean initialized;
    private CompletableFuture<List<TimingData>> pendingRead;
    private volatile CompletableFuture<Void> recoveryCompletion = CompletableFuture.completedFuture(null);

    NodeServiceRecoveryTask(
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

    /** Starts reconstruction up to the recovered committed head. */
    synchronized void recover(long head, SerialTaskRunner taskRunner) {

        failIfInvalidArgument(head >= 0L, "head must not be negative");
        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");

        targetHead = head;
        nextSequence = 1L;
        initialized = false;
        recoveryCompletion = new CompletableFuture<Void>();
        recovering = true;
        wake(taskRunner);
    }

    /** Extends an in-progress reconstruction to include a later commit. */
    synchronized void extendTo(long head, SerialTaskRunner taskRunner) {

        failIfInvalidArgument(head >= 1L, "head must be positive");
        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");

        if (head > targetHead) {
            targetHead = head;
        }
        wake(taskRunner);
    }

    /** Reports whether committed records still belong to reconstruction. */
    boolean isRecovering() {

        return recovering;
    }

    /** Returns completion of the current recovery catch-up. */
    CompletableFuture<Void> recoveryCompletion() {

        return recoveryCompletion;
    }

    /** Cancels current work and resets reconstruction state. */
    synchronized void stop() {

        cancel();
        recovering = false;
        recoveryCompletion.cancel(true);
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

        if (nextSequence <= targetHead) {
            submitNextRead();
            return TaskStep.await(pendingRead);
        }

        state.completeDerivedTimingRecovery();
        recovering = false;

        if (nextSequence <= targetHead) {
            recovering = true;
            return TaskStep.again();
        }

        recoveryCompletion.complete(null);
        return TaskStep.done();
    }

    @Override
    protected void onRunCompleted(Throwable failure) {

        pendingRead = null;
        if (failure != null) {
            state.markDerivedTimingStale();
            recoveryCompletion.completeExceptionally(failure);
            failureHandler.accept(failure);
        }
    }

    /** Submits the next bounded ordered LogBook range read. */
    private void submitNextRead() {

        long remaining = targetHead - nextSequence + 1L;
        int limit = (int) Math.min((long) TIMING_DATA_BATCH_SIZE, remaining);
        pendingRead = logBook.submit(LogBookQueries.timingDataRange(nextSequence, limit));
    }

    /** Applies one completed batch in strict source-sequence order. */
    private void applyCompletedRead() {

        List<TimingData> records = pendingRead.join();
        pendingRead = null;

        failIfIllegalState(!records.isEmpty(), "No TimingData returned for sequence %s", nextSequence);

        for (TimingData data : records) {
            long sequence = data.sequenceNumber();
            failIfIllegalState(sequence == nextSequence, "Expected TimingData sequence %s, got %s", nextSequence, sequence);
            state.applyTimingData(data);
            nextSequence++;
        }
    }
}
