package oss.relay.timing.timingpoint.domain.node.service;

import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingDataTypes.LocationId;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingTimestamp;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookAccessor;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookStatus;
import oss.relay.timing.timingpoint.domain.node.racedata.RaceDataState;
import oss.relay.timing.timingpoint.domain.node.registration.ActiveRegistrations;
import oss.relay.timing.timingpoint.domain.node.registration.StageTiming;
import oss.relay.timing.timingpoint.domain.node.service.state.NodeState;
import oss.relay.timing.timingpoint.domain.node.service.state.NodeStateAccessor;
import oss.relay.timing.timingpoint.domain.node.service.state.NodeStateTestControl;
import oss.relay.timing.timingpoint.infra.lifecycle.LifecycleComponent;
import oss.relay.timing.timingpoint.platform.events.EventInput;
import oss.relay.timing.timingpoint.platform.execution.tasks.AbstractTask;
import oss.relay.timing.timingpoint.platform.execution.tasks.SerialTaskRunner;
import oss.relay.timing.timingpoint.platform.execution.tasks.TaskStep;
import oss.relay.timing.timingpoint.platform.time.TimeSource;
import oss.relay.timing.upstreamdata.UpstreamData;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Processes node-local input on one ordered Task Lane.
 *
 * <p>NodeService normalizes incoming changes and drives NodeState. NodeState owns the retained
 * live and derived data and exposes its read-only NodeStateAccessor boundary.</p>
 */
public final class NodeService extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeService.class);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2L);
    private static final int TASK_QUEUE_CAPACITY = 32;

    /** LogBook availability status input. */
    public static final EventInput<NodeService, LogBookStatus> LOGBOOK_STATUS =
            EventInput.of((service, source) -> source.subscribe(service::onLogBookStatus));

    /** Post-commit signal; DataTask reads the authoritative timing record from LogBook. */
    public static final EventInput<NodeService, TimingRecord> TIMING_DATA_CHANGED =
            EventInput.of((service, source) -> source.subscribe(service::onTimingDataChanged));

    private final NodeId nodeId;
    private final SerialTaskRunner taskRunner;
    private final TimeSource timeSource;
    private final RaceDataState raceData;
    private final NodeState state;
    private final MainTask mainTask = new MainTask();

    /** Creates the service over one LogBook and the node Task Lane. */
    public NodeService(
            NodeId nodeId,
            ScheduledExecutorService scheduler,
            LogBookAccessor logBook,
            TimeSource timeSource,
            RaceDataState raceData,
            ActiveRegistrations activeRegistrations) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        failIfInvalidArgument(activeRegistrations != null, "activeRegistrations must not be null");

        this.nodeId = nodeId;
        this.taskRunner =
                new SerialTaskRunner(
                        TASK_QUEUE_CAPACITY,
                        "NodeService-" + nodeId.value(),
                        scheduler);
        this.timeSource = timeSource;
        this.raceData = raceData;
        this.state =
                new NodeState(
                        taskRunner,
                        logBook,
                        raceData,
                        activeRegistrations,
                        this::dataTaskRebuilt,
                        this::dataTaskFailed);
    }

    /** Starts the service-owned logical task lane. */
    @Override
    protected void onActivate() {

        taskRunner.start();
    }

    /** Stops service tasks and then closes the service-owned lane. */
    @Override
    protected void onDeactivate() {

        mainTask.stop();
        state.stop();
        taskRunner.close();
    }

    /**
     * Applies one ordered Central TEAM_ASSIGNMENTS change set.
     *
     * <p>The complete bounded message is validated against fixed EventData, retained live
     * assignments and the other changes in the same message before any mutation occurs.</p>
     */
    public CompletableFuture<Void> applyTeamAssignments(
            UpstreamData.TeamAssignments changes) {

        failIfInvalidArgument(changes != null, "changes must not be null");

        return taskRunner.runTask(
                () -> {
                    validateTeamAssignments(changes);
                    for (UpstreamData.TeamAssignmentChange change : changes.changes()) {
                        switch (change.operation()) {
                            case SET:
                                state.teamAssignmentSet(
                                        change.registrationId(),
                                        change.teamId());
                                break;
                            case DEL:
                                state.teamAssignmentDeleted(change.registrationId());
                                break;
                            default:
                                throw new IllegalArgumentException(
                                        "unsupported team assignment operation "
                                                + change.operation());
                        }
                    }
                    for (UpstreamData.TeamAssignmentChange change : changes.changes()) {
                        state.teamAssignmentChanged(change.registrationId());
                    }
                    return TaskStep.done();
                });
    }

    /**
     * Applies one ordered Central STAGE_STARTS change set.
     *
     * <p>All SET timestamps are normalized in a validation pass before retained state is changed.
     * The apply pass uses the same offset reference and updates ranking once after the complete
     * message.</p>
     */
    public CompletableFuture<Void> applyStageStarts(
            UpstreamData.StageStarts changes) {

        failIfInvalidArgument(changes != null, "changes must not be null");

        return taskRunner.runTask(
                () -> {
                    applyNormalizedStageStarts(changes);
                    return TaskStep.done();
                });
    }

    /** Validates one bounded assignment message against the final assignment result. */
    private void validateTeamAssignments(
            UpstreamData.TeamAssignments assignments) {

        List<UpstreamData.TeamAssignmentChange> changes = assignments.changes();
        for (int index = 0; index < changes.size(); index++) {
            UpstreamData.TeamAssignmentChange change = changes.get(index);
            if (change.operation() != UpstreamData.ChangeOperation.SET) {
                continue;
            }

            RegistrationId registrationId = change.registrationId();
            TeamId teamId = change.teamId();
            failIfInvalidArgument(
                    !raceData.hasFixedTeamAssignment(registrationId),
                    "active assignment cannot override fixed EventData TeamId for %s",
                    registrationId);

            for (int other = index + 1; other < changes.size(); other++) {
                UpstreamData.TeamAssignmentChange candidate = changes.get(other);
                failIfInvalidArgument(
                        candidate.operation() != UpstreamData.ChangeOperation.SET
                                || !teamId.equals(candidate.teamId()),
                        "one TeamId may not be assigned to multiple registrations: %s",
                        teamId);
            }

            RegistrationId currentOwner = raceData.liveRegistrationIdFor(teamId);
            failIfInvalidArgument(
                    currentOwner == null
                            || currentOwner.equals(registrationId)
                            || releasesTeam(assignments, currentOwner, teamId),
                    "one TeamId may not be assigned to multiple registrations: %s",
                    teamId);
        }
    }

    /** Reports whether one message removes a TeamId from its current live owner. */
    private static boolean releasesTeam(
            UpstreamData.TeamAssignments assignments,
            RegistrationId registrationId,
            TeamId teamId) {

        for (UpstreamData.TeamAssignmentChange change : assignments.changes()) {
            if (!change.registrationId().equals(registrationId)) {
                continue;
            }
            return change.operation() == UpstreamData.ChangeOperation.DEL
                    || !teamId.equals(change.teamId());
        }
        return false;
    }

    /** Validates, normalizes and applies one STAGE_STARTS message without a work buffer. */
    private void applyNormalizedStageStarts(
            UpstreamData.StageStarts changes) {

        Instant offsetReference = timeSource.now();
        failIfInvalidArgument(offsetReference != null, "timeSource.now must not return null");

        for (UpstreamData.StageStartTimeChange change : changes.changes()) {
            if (change.operation() == UpstreamData.ChangeOperation.SET) {
                StageTiming.compactUtc(
                        change.start(),
                        timeSource.zone(),
                        offsetReference);
            }
        }

        boolean stateChanged = false;
        for (UpstreamData.StageStartTimeChange change : changes.changes()) {
            switch (change.operation()) {
                case SET:
                    stateChanged |=
                            state.stageStartSet(
                                    change.registrationId(),
                                    StageTiming.compactUtc(
                                            change.start(),
                                            timeSource.zone(),
                                            offsetReference));
                    break;
                case DEL:
                    stateChanged |= state.stageStartDeleted(change.registrationId());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unsupported stage start operation " + change.operation());
            }
        }
        state.stageStartChangesApplied(stateChanged);
    }

    /** Requests a complete registration-state rebuild when TimingNode opens one WP. */
    public void locationOpened(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        state.locationOpened(locationId);
    }

    /** Deactivates registration state when TimingNode closes its WP. */
    public void locationClosed() {

        state.locationClosed();
    }

    /** Exposes current node data without exposing its mutation API. */
    public NodeStateAccessor state() {

        return state;
    }

    /** Exposes engineering/test mutations without widening the normal state accessor. */
    public NodeStateTestControl stateTestControl() {

        return state;
    }

    /** Passes LogBook availability changes to MainTask. */
    private void onLogBookStatus(LogBookStatus status) {

        failIfInvalidArgument(status != null, "status must not be null");
        mainTask.statusChanged(status);
    }

    /** Treats a committed TimingRecord as a wake signal; NodeState rereads LogBook by sequence. */
    private void onTimingDataChanged(TimingRecord record) {

        failIfInvalidArgument(record != null, "TimingRecord must not be null");
        state.dataChanged();
    }

    /** Logs one completed historical reconstruction for an OPEN WP. */
    private void dataTaskRebuilt(int locationId, long recordsProcessed) {

        LOG.info(
                "NodeService OPEN rebuild completed: node={}, location={}, recordsProcessed={}",
                nodeId.value(),
                locationId,
                recordsProcessed);
    }

    /** Logs a failed DataTask catch-up. */
    private void dataTaskFailed(Throwable failure) {

        LOG.error("NodeService DataTask failed for node {}", nodeId.value(), failure);
    }

    /** Handles only LogBook availability transitions. */
    private final class MainTask extends AbstractTask {
        private LogBookStatus status = LogBookStatus.INIT;

        /** Stores the latest LogBook status and wakes MainTask. */
        private synchronized void statusChanged(LogBookStatus newStatus) {

            status = newStatus;
            wake(taskRunner);
        }

        /** Stops MainTask and resets its status. */
        private synchronized void stop() {

            cancel();
            status = LogBookStatus.INIT;
        }

        /** MainTask keeps its status between wake-driven runs. */
        @Override
        protected void resetForRun() {
            // Status is updated by LogBook events.
        }

        /** Applies the latest LogBook availability state. */
        @Override
        public TaskStep runStep() {

            switch (currentStatus()) {
                case LOADED:
                    state.logBookAvailable();
                    break;
                case ERROR:
                    state.logBookUnavailable();
                    break;
                case INIT:
                    break;
                default:
                    throw new IllegalStateException("Unsupported LogBook status");
            }
            return TaskStep.done();
        }

        /** Returns the latest LogBook status. */
        private synchronized LogBookStatus currentStatus() {

            return status;
        }
    }
}
