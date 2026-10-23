package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.state.NodeState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.state.NodeStateAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.state.NodeStateTestControl;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

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
     * Applies one ordered Central RACE_DATA change set.
     *
     * <p>NodeUpstream dispatches this to the independent NodeService task lane. Stage-start
     * values are normalized once to compact UTC while preserving whether the source supplied a
     * date. Team assignment changes are then reflected in RegisteredTeams.</p>
     */
    public CompletableFuture<Void> applyRaceData(UpstreamData.RaceData changes) {

        failIfInvalidArgument(changes != null, "changes must not be null");

        return taskRunner.runTask(
                () -> {
                    applyNormalizedRaceData(changes);
                    return TaskStep.done();
                });
    }

    /** Converts one protocol change set to domain values before mutating retained race state. */
    private void applyNormalizedRaceData(UpstreamData.RaceData changes) {

        Instant offsetReference = timeSource.now();
        failIfInvalidArgument(offsetReference != null, "timeSource.now must not return null");

        Map<RegistrationId, TeamId> assignmentsToSet =
                new LinkedHashMap<RegistrationId, TeamId>();
        List<RegistrationId> assignmentsToDelete =
                new ArrayList<RegistrationId>();
        for (UpstreamData.TeamAssignmentChange change : changes.teamAssignmentChanges()) {
            failIfInvalidArgument(change != null, "team assignment change must not be null");
            switch (change.operation()) {
                case SET:
                    assignmentsToSet.put(change.registrationId(), change.teamId());
                    break;
                case DEL:
                    assignmentsToDelete.add(change.registrationId());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unsupported team assignment operation " + change.operation());
            }
        }

        Map<RegistrationId, Long> stageStartsToSet =
                new LinkedHashMap<RegistrationId, Long>();
        List<RegistrationId> stageStartsToDelete =
                new ArrayList<RegistrationId>();
        for (UpstreamData.StageStartTimeChange change : changes.stageStartTimeChanges()) {
            failIfInvalidArgument(change != null, "stage start change must not be null");
            switch (change.operation()) {
                case SET:
                    stageStartsToSet.put(
                            change.registrationId(),
                            StageTiming.compactUtc(
                                    change.start(),
                                    timeSource.zone(),
                                    offsetReference));
                    break;
                case DEL:
                    stageStartsToDelete.add(change.registrationId());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unsupported stage start operation " + change.operation());
            }
        }

        state.applyRaceDataChanges(
                assignmentsToSet,
                assignmentsToDelete,
                stageStartsToSet,
                stageStartsToDelete);

        for (UpstreamData.TeamAssignmentChange change : changes.teamAssignmentChanges()) {
            state.teamAssignmentChanged(change.registrationId());
        }
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
