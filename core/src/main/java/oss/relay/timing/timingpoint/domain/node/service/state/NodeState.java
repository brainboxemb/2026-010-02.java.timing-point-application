package oss.relay.timing.timingpoint.domain.node.service.state;

import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.LocationId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookAccessor;
import oss.relay.timing.timingpoint.domain.node.racedata.RaceDataState;
import oss.relay.timing.timingpoint.domain.node.registration.ActiveRegistrations;
import oss.relay.timing.timingpoint.domain.node.registration.RegisteredTeams;
import oss.relay.timing.timingpoint.domain.node.registration.StageTiming;
import oss.relay.timing.timingpoint.platform.events.EventSource;
import oss.relay.timing.timingpoint.platform.execution.tasks.SerialTaskRunner;
import oss.relay.timing.timingpoint.platform.execution.tasks.TaskStep;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns current live and derived data for one TimingNode.
 *
 * <p>NodeService supplies mutations on its Task Lane. The NodeStateAccessor contract exposes
 * ordered reads to other components. Active-WP reads wait until the LogBook projection has caught
 * up, so callers never observe a partial reconstruction.</p>
 */
public final class NodeState implements NodeStateAccessor, NodeStateTestControl {
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2L);

    private final SerialTaskRunner taskRunner;
    private final RaceDataState raceData;
    private final ActiveRegistrations activeRegistrations;
    private final RegisteredTeams registeredTeams = new RegisteredTeams();
    private final DataTask dataTask;

    /** Binds retained state and its LogBook projection task to the NodeService Task Lane. */
    public NodeState(
            SerialTaskRunner taskRunner,
            LogBookAccessor logBook,
            RaceDataState raceData,
            ActiveRegistrations activeRegistrations,
            java.util.function.BiConsumer<Integer, Long> rebuildCompletedHandler,
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

    /** Applies one already validated live assignment SET to retained state. */
    public void teamAssignmentSet(
            RegistrationId registrationId,
            TeamId teamId) {

        raceData.assignTeam(registrationId, teamId);
    }

    /** Applies one live assignment DEL to retained state. */
    public void teamAssignmentDeleted(RegistrationId registrationId) {

        raceData.removeTeam(registrationId);
    }

    /** Refreshes derived team membership from the final retained assignment state. */
    public void teamAssignmentChanged(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        dataTask.teamAssignmentChanged(registrationId, raceData.teamIdFor(registrationId));
    }

    /** Applies one normalized stage-start SET and returns whether retained state changed. */
    public boolean stageStartSet(
            RegistrationId registrationId,
            long stageStart) {

        boolean changed = raceData.stageStartSet(registrationId, stageStart);
        dataTask.stageStartSet(registrationId, stageStart);
        return changed;
    }

    /** Applies one stage-start DEL and returns whether retained state changed. */
    public boolean stageStartDeleted(RegistrationId registrationId) {

        boolean changed = raceData.stageStartDeleted(registrationId);
        dataTask.stageStartDeleted(registrationId);
        return changed;
    }

    /** Finalizes one complete STAGE_STARTS message and recalculates ranking at most once. */
    public void stageStartChangesApplied(boolean stateChanged) {

        raceData.stageStartChangesApplied(stateChanged);
        dataTask.stageStartChangesApplied();
    }

    /** Applies one absolute engineering override using the same compact UTC state as RaceData. */
    @Override
    public void overrideStageStart(
            RegistrationId registrationId,
            long startMillis) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        long stageStart =
                StageTiming.compactUtc(
                        Instant.ofEpochMilli(startMillis));
        runOrderedMutation(
                () -> {
                    raceData.setStageStart(registrationId, stageStart);
                    dataTask.stageStartChanged(
                            registrationId,
                            Long.valueOf(stageStart));
                });
    }

    /** Starts reconstruction for the newly opened WP before active-state reads may complete. */
    public void locationOpened(LocationId locationId) {

        dataTask.locationOpened(locationId);
    }

    /** Invalidates the active-WP projection when the TimingNode closes. */
    public void locationClosed() {

        dataTask.locationClosed();
    }

    /** Makes LogBook-backed projection work eligible after LogBook loading completed. */
    public void logBookAvailable() {

        dataTask.logBookAvailable();
    }

    /** Prevents active projection reads from succeeding while LogBook is unavailable. */
    public void logBookUnavailable() {

        dataTask.unavailable();
    }

    /** Coalesces a committed TimingRecord into projection catch-up work. */
    public void dataChanged() {

        dataTask.dataChanged();
    }

    /** Cancels projection work and clears transient active-WP data during NodeService deactivation. */
    public void stop() {

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
                activeRegistrations.lastRevokeSequence(),
                raceData.stageStartRevision());
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

    /** Reads current StageStartTimes and metadata in one ordered Task Lane turn. */
    @Override
    public StageStartProjectionInfo visitStageStarts(
            BiConsumer<RegistrationId, Long> visitor) {

        failIfInvalidArgument(visitor != null, "visitor must not be null");
        return readOrdered(
                () -> new StageStartProjectionInfo(
                        raceData.visitStageStarts(visitor),
                        raceData.stageStartRevision()));
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

    /** Runs one short engineering mutation on the Task Lane and waits for completion. */
    private void runOrderedMutation(Runnable operation) {

        failIfInvalidArgument(operation != null, "operation must not be null");
        if (taskRunner.isExecutingOnCurrentThread()) {
            operation.run();
            return;
        }

        taskRunner.await(
                taskRunner.runTask(
                        () -> {
                            operation.run();
                            return TaskStep.done();
                        }),
                QUERY_TIMEOUT);
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
