package oss.relay.timing.timingpoint.domain.node.service.state;

import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingDataTypes.LocationId;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookAccessor;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookQueries;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookQuery;
import oss.relay.timing.timingpoint.domain.node.racedata.RaceDataUpdate;
import oss.relay.timing.timingpoint.domain.node.registration.ActiveRegistrations;
import oss.relay.timing.timingpoint.platform.execution.tasks.AbstractTask;
import oss.relay.timing.timingpoint.platform.execution.tasks.SerialTaskRunner;
import oss.relay.timing.timingpoint.platform.execution.tasks.TaskStep;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfIllegalState;
import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Maintains the registration projections for the currently open WP from LogBook TimingData.
 *
 * <p>CLOSED requires no projection. OPEN(locationId) requests a fresh bounded replay from LogBook
 * sequence one; ActiveRegistrations filters that replay to the selected LocationId and
 * RegisteredTeams rebuilds silently from the resulting validity changes. After catch-up, later
 * commit signals continue from the retained LogBook cursor and publish only live team membership
 * transitions.</p>
 *
 * <p>Open/close requests may arrive while a bounded LogBook read is pending. A request generation
 * makes that partial result obsolete: the next task turn discards it, resets the projection and
 * starts the newly requested WP from sequence one. Reads wait on caughtUp and therefore never
 * expose that temporary partial state.</p>
 */
final class DataTask extends AbstractTask {
    private static final int TIMING_DATA_BATCH_SIZE = 64;

    private final SerialTaskRunner taskRunner;
    private final LogBookAccessor logBook;
    private final NodeState state;
    private final java.util.function.BiConsumer<Integer, Long> rebuildCompletedHandler;
    private final Consumer<Throwable> failureHandler;

    private boolean available;
    private boolean unavailable;
    private boolean reloadRequested;
    private int requestedLocationId;
    private long signalVersion;

    private int activeLocationId;
    private boolean rebuilding;
    private long nextSequence = 1L;
    private CompletableFuture<List<TimingRecord>> pendingRead;
    private volatile CompletableFuture<Void> caughtUp =
            CompletableFuture.completedFuture(null);

    DataTask(
            SerialTaskRunner taskRunner,
            LogBookAccessor logBook,
            NodeState state,
            java.util.function.BiConsumer<Integer, Long> rebuildCompletedHandler,
            Consumer<Throwable> failureHandler) {

        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(state != null, "state must not be null");
        failIfInvalidArgument(
                rebuildCompletedHandler != null,
                "rebuildCompletedHandler must not be null");
        failIfInvalidArgument(failureHandler != null, "failureHandler must not be null");

        this.taskRunner = taskRunner;
        this.logBook = logBook;
        this.state = state;
        this.rebuildCompletedHandler = rebuildCompletedHandler;
        this.failureHandler = failureHandler;
    }

    /** Marks LogBook readable; an already-open WP is rebuilt from sequence one. */
    synchronized void logBookAvailable() {

        available = true;
        unavailable = false;
        if (requestedLocationId != 0) {
            reloadRequested = true;
            signalVersion++;
            beginCatchUp();
            wake(taskRunner);
        }
    }

    /** Requests activation and complete historical reconstruction for one open WP. */
    synchronized void locationOpened(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");

        requestedLocationId = locationId.value();
        reloadRequested = true;
        signalVersion++;
        beginCatchUp();
        if (unavailable) {
            caughtUp.completeExceptionally(new IllegalStateException("LogBook is in ERROR state"));
        }
        wake(taskRunner);
    }

    /** Requests deactivation and clearing of the current WP projection. */
    synchronized void locationClosed() {

        requestedLocationId = 0;
        reloadRequested = true;
        signalVersion++;
        beginCatchUp();
        wake(taskRunner);
    }

    /**
     * Applies one live RaceData assignment change only when the current WP projection request
     * has not already been superseded by CLOSE or another OPEN.
     */
    void teamAssignmentChanged(RegistrationId registrationId, TeamId teamId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        if (isCurrentProjection()) {
            state.registeredTeams().teamAssignmentChanged(registrationId, teamId);
        }
    }

    /** Applies one validated bounded RaceData transaction to the active display projection. */
    void raceDataApplied(RaceDataUpdate changes) {

        failIfInvalidArgument(changes != null, "changes must not be null");
        if (isCurrentProjection()) {
            state.activeRegistrations().applyStageStartChanges(changes);
        }
    }

    /** Applies one engineering stage-start override to the active display projection. */
    void stageStartChanged(
            RegistrationId registrationId,
            Long stageStart) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        if (isCurrentProjection()) {
            state.activeRegistrations().stageStartChanged(
                    registrationId,
                    stageStart);
        }
    }

    /** Wakes incremental catch-up after a new committed timing record. */
    synchronized void dataChanged() {

        if (available && requestedLocationId != 0) {
            signalVersion++;
            beginCatchUp();
            wake(taskRunner);
        }
    }

    /** Makes active projection reads fail while LogBook is unavailable. */
    synchronized void unavailable() {

        available = false;
        unavailable = true;
        if (requestedLocationId != 0) {
            beginCatchUp();
            caughtUp.completeExceptionally(new IllegalStateException("LogBook is in ERROR state"));
        }
    }

    /** Returns completion after the requested open WP has caught up to LogBook. */
    CompletionStage<Void> caughtUp() {

        return caughtUp;
    }

    /** Verifies that a fully reconstructed WP projection is currently active. */
    void requireActive() {

        failIfIllegalState(
                activeLocationId != 0 && !rebuilding,
                "registration projection requires an open WP");
    }

    /** Cancels active work and clears transient registration state. */
    synchronized void stop() {

        cancel();
        available = false;
        unavailable = false;
        reloadRequested = false;
        requestedLocationId = 0;
        signalVersion++;
        activeLocationId = 0;
        rebuilding = false;
        nextSequence = 1L;
        pendingRead = null;
        state.activeRegistrations().deactivate();
        state.registeredTeams().deactivate();
        caughtUp.cancel(true);
        caughtUp = CompletableFuture.completedFuture(null);
    }

    /** Clears one-run asynchronous state before a new run starts. */
    @Override
    protected void resetForRun() {

        pendingRead = null;
    }

    /** Rebuilds or incrementally catches up the currently requested WP projection. */
    @Override
    public TaskStep runStep() {

        if (takeReloadRequest()) {
            resetProjection();
        }

        if (activeLocationId == 0) {
            long observedVersion = currentSignalVersion();
            if (!completeCatchUp(observedVersion)) {
                return TaskStep.again();
            }
            return TaskStep.done();
        }

        if (!isAvailable()) {
            return TaskStep.done();
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

    /** Applies the newest open/close request to Task Lane-owned projection state. */
    private synchronized boolean takeReloadRequest() {

        if (!reloadRequested) {
            return false;
        }

        activeLocationId = requestedLocationId;
        reloadRequested = false;
        return true;
    }

    /** Clears stale state and starts either an inactive state or a fresh WP reconstruction. */
    private void resetProjection() {

        pendingRead = null;
        nextSequence = 1L;

        if (activeLocationId == 0) {
            rebuilding = false;
            state.activeRegistrations().deactivate();
            state.registeredTeams().deactivate();
        } else {
            rebuilding = true;
            state.activeRegistrations().activate(new LocationId(activeLocationId));
            state.registeredTeams().beginRebuild();
        }
    }

    /** Starts the next bounded ordered LogBook range read. */
    private void submitNextRead(long head) {

        long remaining = head - nextSequence + 1L;
        int limit = (int) Math.min((long) TIMING_DATA_BATCH_SIZE, remaining);
        pendingRead = logBook.submit(LogBookQueries.timingRecordRange(nextSequence, limit));
    }

    /** Applies one completed LogBook batch in source order to the selected WP only. */
    private void applyCompletedRead() {

        List<TimingRecord> records = pendingRead.join();
        pendingRead = null;

        failIfIllegalState(!records.isEmpty(), "No timing record returned for sequence %s", nextSequence);

        for (TimingRecord record : records) {
            long sequence = record.sequenceNumber();
            failIfIllegalState(sequence == nextSequence, "Expected sequence %s, got %s", nextSequence, sequence);

            Long stageStart = stageStart(record);
            ActiveRegistrations.Change change =
                    state.activeRegistrations().apply(
                            record,
                            stageStart);
            if (change != null) {
                state.registeredTeams().apply(change, state.raceData().teamIdFor(change.registrationId()));
            }
            nextSequence++;
        }
    }

    /** Resolves the current RaceData start for a registration ADD before retaining its row. */
    private Long stageStart(TimingRecord record) {

        if ((record.kind() == TimingRecord.Kind.AUTOMATIC_REGISTRATION
                        || record.kind() == TimingRecord.Kind.MANUAL_REGISTRATION)
                && record.action() == TimingRecord.Action.ADD) {
            return state.raceData().stageStartFor(
                    RegistrationId.fromPackedValue(
                            record.registrationId()));
        }
        return null;
    }

    /** Returns whether the active Task Lane projection still matches the newest OPEN request. */
    private synchronized boolean isCurrentProjection() {

        return activeLocationId != 0
                && !reloadRequested
                && requestedLocationId == activeLocationId;
    }

    /** Returns whether LogBook can currently serve reconstruction reads. */
    private synchronized boolean isAvailable() {

        return available;
    }

    /** Returns the latest open/close/data-change generation. */
    private synchronized long currentSignalVersion() {

        return signalVersion;
    }

    /**
     * Completes catch-up only when no newer request or commit signal arrived.
     *
     * <p>The first stable completion after OPEN also enables live team +/- publication. All
     * historical replay before that point is intentionally silent.</p>
     */
    private synchronized boolean completeCatchUp(long observedVersion) {

        if (observedVersion != signalVersion) {
            return false;
        }

        if (activeLocationId != 0 && rebuilding) {
            state.activeRegistrations().completeRebuild();
            state.registeredTeams().completeRebuild();
            rebuilding = false;
            rebuildCompletedHandler.accept(
                    Integer.valueOf(activeLocationId),
                    Long.valueOf(nextSequence - 1L));
        }
        caughtUp.complete(null);
        return true;
    }

    /** Starts a new completion when the previous requested state was already settled. */
    private void beginCatchUp() {

        if (caughtUp.isDone()) {
            caughtUp = new CompletableFuture<Void>();
        }
    }
}
