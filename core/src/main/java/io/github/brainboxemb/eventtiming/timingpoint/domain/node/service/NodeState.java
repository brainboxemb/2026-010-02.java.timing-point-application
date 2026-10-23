package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.RegisteredTeams;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns current live and derived data for one TimingNode.
 *
 * <p>Mutations are package-private and run on the NodeService Task Lane. The public
 * NodeStateAccessor contract exposes ordered reads only. Active-WP reads wait until the LogBook
 * projection has caught up, so callers never observe a partial reconstruction.</p>
 */
final class NodeState implements NodeStateAccessor {
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2L);

    private final SerialTaskRunner taskRunner;
    private final RaceDataState raceData;
    private final ActiveRegistrations activeRegistrations;
    private final RegisteredTeams registeredTeams = new RegisteredTeams();
    private final DataTask dataTask;

    /** Binds retained state and its LogBook projection task to the NodeService Task Lane. */
    NodeState(
            SerialTaskRunner taskRunner,
            LogBookAccessor logBook,
            RaceDataState raceData,
            ActiveRegistrations activeRegistrations,
            DataTask.RebuildCompletedHandler rebuildCompletedHandler,
            Consumer<Throwable> failureHandler) {

        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        failIfInvalidArgument(activeRegistrations != null, "activeRegistrations must not be null");
        failIfInvalidArgument(rebuildCompletedHandler != null, "rebuildCompletedHandler must not be null");
        failIfInvalidArgument(failureHandler != null, "failureHandler must not be null");

        this.taskRunner = taskRunner;
        this.raceData = raceData;
        this.activeRegistrations = activeRegistrations;
        this.dataTask =
                new DataTask(
                        taskRunner,
                        logBook,
                        this,
                        rebuildCompletedHandler,
                        failureHandler);
    }

    /** Applies one validated live RaceData change and keeps the active registration projection aligned. */
    void applyRaceDataChanges(
            Map<RegistrationId, TeamId> assignmentsToSet,
            List<RegistrationId> assignmentsToDelete,
            Map<RegistrationId, Long> stageStartsToSet,
            List<RegistrationId> stageStartsToDelete) {

        raceData.applyChanges(
                assignmentsToSet,
                assignmentsToDelete,
                stageStartsToSet,
                stageStartsToDelete);
        dataTask.stageStartChangesApplied(stageStartsToSet, stageStartsToDelete);
    }

    /** Re-evaluates active team membership after one live assignment changed. */
    void teamAssignmentChanged(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        dataTask.teamAssignmentChanged(registrationId, raceData.teamIdFor(registrationId));
    }

    /** Applies one engineering stage start to live state and the active registration projection. */
    void setStageStart(RegistrationId registrationId, long startMillis) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        raceData.setStageStart(registrationId, startMillis);
        dataTask.stageStartChanged(registrationId, Long.valueOf(startMillis));
    }

    /** Starts reconstruction for the newly opened WP before active-state reads may complete. */
    void locationOpened(LocationId locationId) {

        dataTask.locationOpened(locationId);
    }

    /** Invalidates the active-WP projection when the TimingNode closes. */
    void locationClosed() {

        dataTask.locationClosed();
    }

    /** Makes LogBook-backed projection work eligible after LogBook loading completed. */
    void logBookAvailable() {

        dataTask.logBookAvailable();
    }

    /** Prevents active projection reads from succeeding while LogBook is unavailable. */
    void logBookUnavailable() {

        dataTask.unavailable();
    }

    /** Coalesces a committed TimingRecord into projection catch-up work. */
    void dataChanged() {

        dataTask.dataChanged();
    }

    /** Cancels projection work and clears transient active-WP data during NodeService deactivation. */
    void stop() {

        dataTask.stop();
    }

    /** Gives DataTask direct access to the lane-owned RaceData state without widening the public API. */
    RaceDataState raceData() {

        return raceData;
    }

    /** Gives DataTask direct access to the lane-owned registration projection. */
    ActiveRegistrations activeRegistrations() {

        return activeRegistrations;
    }

    /** Gives DataTask direct access to the lane-owned unique-team projection. */
    RegisteredTeams registeredTeams() {

        return registeredTeams;
    }

    /** Team change events are published only after the active-WP reconstruction is complete. */
    @Override
    public EventSource<RegisteredTeams.Change> registeredTeamsChanged() {

        return registeredTeams.CHANGED;
    }

    /** Waits for active-WP catch-up and reads all projection metadata in one Task Lane turn. */
    @Override
    public RegistrationProjectionInfo registrationProjectionInfo() {

        return readActive(this::currentRegistrationProjectionInfo);
    }

    /** Keeps page rows and returned metadata on the same caught-up Task Lane turn. */
    @Override
    public RegistrationProjectionInfo visitRegistrationPage(
            long afterSequence,
            int limit,
            Consumer<ActiveRegistrations.Registration> visitor) {

        failIfInvalidArgument(afterSequence >= 0L, "afterSequence must not be negative");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        return readActive(
                () -> {
                    activeRegistrations.visitAfter(afterSequence, limit, visitor);
                    return currentRegistrationProjectionInfo();
                });
    }

    /** Reads projection metadata only after the caller has entered the Task Lane. */
    private RegistrationProjectionInfo currentRegistrationProjectionInfo() {

        return new RegistrationProjectionInfo(
                activeRegistrations.locationId(),
                activeRegistrations.size(),
                registeredTeams.uniqueTeamCount(),
                activeRegistrations.lastMutationSequence(),
                activeRegistrations.lastRevokeSequence());
    }

    /** Waits for active-WP catch-up before returning the maintained unique-team count. */
    @Override
    public int uniqueTeamRegistrationCount() {

        return readActive(registeredTeams::uniqueTeamCount);
    }

    /** Visits only the requested bounded prefix after active-WP catch-up. */
    @Override
    public int visitRegistrations(
            int limit,
            Consumer<ActiveRegistrations.Registration> visitor) {

        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");
        return readActive(() -> activeRegistrations.visit(limit, visitor));
    }

    /** Pages later registration rows without exposing a partially rebuilt projection. */
    @Override
    public int visitRegistrationsAfter(
            long afterSequence,
            int limit,
            Consumer<ActiveRegistrations.Registration> visitor) {

        failIfInvalidArgument(afterSequence >= 0L, "afterSequence must not be negative");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");
        return readActive(() -> activeRegistrations.visitAfter(afterSequence, limit, visitor));
    }

    /** Visits the complete maintained team set only after active-WP catch-up. */
    @Override
    public int visitRegisteredTeams(Consumer<TeamId> visitor) {

        failIfInvalidArgument(visitor != null, "visitor must not be null");
        return readActive(() -> registeredTeams.visit(visitor));
    }

    /** Reads live RaceData in one Task Lane turn without depending on LogBook projection catch-up. */
    @Override
    public void visitRaceData(
            BiConsumer<RegistrationId, TeamId> assignmentVisitor,
            BiConsumer<RegistrationId, Long> stageStartVisitor) {

        failIfInvalidArgument(assignmentVisitor != null, "assignmentVisitor must not be null");
        failIfInvalidArgument(stageStartVisitor != null, "stageStartVisitor must not be null");

        readOrdered(
                () -> {
                    raceData.visitLiveAssignments(assignmentVisitor);
                    raceData.visitStageStarts(stageStartVisitor);
                    return null;
                });
    }

    /** Executes one state read on the Task Lane without waiting for the LogBook projection. */
    private <T> T readOrdered(Supplier<T> operation) {

        return StateQuery.execute(taskRunner, QUERY_TIMEOUT, null, operation);
    }

    /** Waits cooperatively for active-WP catch-up before executing one state read on the Task Lane. */
    private <T> T readActive(Supplier<T> operation) {

        return StateQuery.execute(
                taskRunner,
                QUERY_TIMEOUT,
                dataTask.caughtUp(),
                () -> {
                    dataTask.requireActive();
                    return operation.get();
                });
    }

}
