package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.TeamRanking;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Keeps NodeService data caught up with committed LogBook TimingData.
 *
 * <p>The task owns the LogBook cursor, bounded asynchronous reads and catch-up completion.
 * NodeService only starts a full load after LogBook becomes LOADED and wakes this task
 * after later TimingData commits.</p>
 */
final class DataTask extends AbstractTask {
    private static final int TIMING_DATA_BATCH_SIZE = 64;

    private final SerialTaskRunner taskRunner;
    private final LogBookAccessor logBook;
    private final RaceDataState raceData;
    private final ActiveRegistrations activeRegistrations;
    private final TeamRanking teamRanking;
    private final Consumer<Throwable> failureHandler;

    private boolean available;
    private boolean loadFromStart;
    private long signalVersion;
    private long nextSequence = 1L;
    private CompletableFuture<List<TimingData>> pendingRead;
    private volatile CompletableFuture<Void> caughtUp = new CompletableFuture<Void>();

    DataTask(
            SerialTaskRunner taskRunner,
            LogBookAccessor logBook,
            RaceDataState raceData,
            ActiveRegistrations activeRegistrations,
            TeamRanking teamRanking,
            Consumer<Throwable> failureHandler) {

        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        failIfInvalidArgument(activeRegistrations != null, "activeRegistrations must not be null");
        failIfInvalidArgument(teamRanking != null, "teamRanking must not be null");
        failIfInvalidArgument(failureHandler != null, "failureHandler must not be null");

        this.taskRunner = taskRunner;
        this.logBook = logBook;
        this.raceData = raceData;
        this.activeRegistrations = activeRegistrations;
        this.teamRanking = teamRanking;
        this.failureHandler = failureHandler;
    }

    /** Starts a complete load from sequence one after LogBook becomes LOADED. */
    synchronized void loadAll() {

        available = true;
        loadFromStart = true;
        signalVersion++;
        beginCatchUp();
        wake(taskRunner);
    }

    /** Wakes catch-up after a new committed TimingData record. */
    synchronized void dataChanged() {

        if (available) {
            signalVersion++;
            beginCatchUp();
            wake(taskRunner);
        }
    }

    /** Makes service reads fail while LogBook is unavailable. */
    synchronized void unavailable() {

        available = false;
        caughtUp.completeExceptionally(new IllegalStateException("LogBook is in ERROR state"));
    }

    /** Returns completion after all signalled LogBook changes have been applied. */
    CompletionStage<Void> caughtUp() {

        return caughtUp;
    }

    /** Cancels active work and resets the task for later activation. */
    synchronized void stop() {

        cancel();
        available = false;
        loadFromStart = false;
        nextSequence = 1L;
        pendingRead = null;
        caughtUp.cancel(true);
        caughtUp = new CompletableFuture<Void>();
    }

    /** Clears one-run asynchronous state before a new run starts. */
    @Override
    protected void resetForRun() {

        pendingRead = null;
    }

    /** Reads and applies missing TimingData until the current LogBook head is reached. */
    @Override
    public TaskStep runStep() {

        if (takeLoadFromStart()) {
            activeRegistrations.reset();
            teamRanking.reset();
            nextSequence = 1L;
        }

        if (pendingRead != null) {
            applyCompletedRead();
        }

        long observedVersion = currentSignalVersion();
        long head =
                logBook.process(
                        LogBookQueries.timingDataHead(),
                        LogBookQuery.ReadConsistency.CURRENT);
        if (nextSequence <= head) {
            submitNextRead(head);
            return TaskStep.await(pendingRead);
        }

        if (!completeCatchUp(observedVersion)) {
            return TaskStep.again();
        }
        return TaskStep.done();
    }

    /** Reports an asynchronous catch-up failure. */
    @Override
    protected void onRunCompleted(Throwable failure) {

        pendingRead = null;
        if (failure != null) {
            caughtUp.completeExceptionally(failure);
            failureHandler.accept(failure);
        }
    }

    /** Returns and clears a pending full-load request. */
    private synchronized boolean takeLoadFromStart() {

        boolean requested = loadFromStart;
        loadFromStart = false;
        return requested;
    }

    /** Starts the next bounded ordered LogBook range read. */
    private void submitNextRead(long head) {

        long remaining = head - nextSequence + 1L;
        int limit = (int) Math.min((long) TIMING_DATA_BATCH_SIZE, remaining);
        pendingRead = logBook.submit(LogBookQueries.timingDataRange(nextSequence, limit));
    }

    /** Applies one completed TimingData batch in source order. */
    private void applyCompletedRead() {

        List<TimingData> records = pendingRead.join();
        pendingRead = null;

        failIfIllegalState(!records.isEmpty(), "No TimingData returned for sequence %s", nextSequence);

        for (TimingData data : records) {
            long sequence = data.sequenceNumber();
            failIfIllegalState(sequence == nextSequence, "Expected sequence %s, got %s", nextSequence, sequence);

            ActiveRegistrations.Change change =
                    activeRegistrations.apply(data, raceData::teamIdFor);
            teamRanking.apply(change);
            nextSequence++;
        }
    }

    /** Returns the latest LogBook-change signal generation. */
    private synchronized long currentSignalVersion() {

        return signalVersion;
    }

    /** Completes catch-up only when no newer signal arrived during the final head read. */
    private synchronized boolean completeCatchUp(long observedVersion) {

        if (observedVersion != signalVersion) {
            return false;
        }
        caughtUp.complete(null);
        return true;
    }

    /** Starts a new catch-up completion after the previous catch-up finished. */
    private void beginCatchUp() {

        if (caughtUp.isDone()) {
            caughtUp = new CompletableFuture<Void>();
        }
    }
}
