package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.TeamRank;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.TeamRanking;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.time.Duration;
import java.time.ZoneId;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Coordinates node-local Task Lane data.
 *
 * <p>MainTask handles only LogBook availability. DataTask owns TimingData catch-up and
 * bounded LogBook reads. The individual domain objects own their data.</p>
 */
public final class NodeService extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeService.class);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2L);

    /** LogBook availability status input. */
    public static final EventInput<NodeService, LogBookStatus> LOGBOOK_STATUS =
            EventInput.of((service, source) -> source.subscribe(service::onLogBookStatus));

    /** Post-commit signal; DataTask reads the authoritative TimingData from LogBook. */
    public static final EventInput<NodeService, TimingData> TIMING_DATA_CHANGED =
            EventInput.of((service, source) -> source.subscribe(service::onTimingDataChanged));

    private final NodeId nodeId;
    private final SerialTaskRunner taskRunner;
    private final RaceDataState raceData;
    private final ActiveRegistrations activeRegistrations;
    private final StageTiming stageTiming;
    private final TeamRanking teamRanking;
    private final DataTask dataTask;
    private final MainTask mainTask = new MainTask();

    /** Creates the service over one LogBook and the node Task Lane. */
    public NodeService(
            NodeId nodeId,
            SerialTaskRunner taskRunner,
            LogBookAccessor logBook,
            EventData eventData,
            RaceDataState raceData,
            ActiveRegistrations activeRegistrations) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(eventData != null, "eventData must not be null");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        failIfInvalidArgument(activeRegistrations != null, "activeRegistrations must not be null");

        this.nodeId = nodeId;
        this.taskRunner = taskRunner;
        this.raceData = raceData;
        this.activeRegistrations = activeRegistrations;
        stageTiming = new StageTiming(eventData, raceData.teamAssignments(), raceData.stageStartTimes());
        teamRanking = new TeamRanking(stageTiming);
        dataTask =
                new DataTask(
                        taskRunner,
                        logBook,
                        raceData,
                        activeRegistrations,
                        teamRanking,
                        this::dataTaskFailed);
    }

    /** Verifies that TimingNode started the shared Task Lane first. */
    @Override
    protected void onActivate() {

        failIfIllegalState(taskRunner.isRunning(), "TimingNode Task Lane is not running");
    }

    /** Stops service tasks. */
    @Override
    protected void onDeactivate() {

        mainTask.stop();
        dataTask.stop();
    }

    /**
     * Applies one ordered Central RACE_DATA change set.
     *
     * <p>UpstreamHandler calls this while the node Task Lane is current. Team assignment
     * changes are then reflected immediately in active-registration counts and ranking.</p>
     */
    public void applyRaceData(UpstreamData.RaceData changes) {

        failIfInvalidArgument(changes != null, "changes must not be null");

        raceData.applyChanges(changes);
        for (UpstreamData.TeamAssignmentChange change : changes.teamAssignmentChanges()) {
            RegistrationId registrationId = change.registrationId();
            TeamId teamId = raceData.teamIdFor(registrationId);
            activeRegistrations.teamAssignmentChanged(registrationId, teamId);
            teamRanking.teamAssignmentChanged(registrationId, teamId);
        }
    }

    /** Returns the number of uniquely registered teams at one location. */
    public int uniqueTeamRegistrationCount(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");

        Supplier<Integer> readCount = () -> activeRegistrations.uniqueTeamCount(locationId);
        return read(readCount);
    }

    /** Visits at most {@code limit} active stage passages. */
    public int visitStageTimes(ZoneId raceTimeZone, int limit, Consumer<StageTime> visitor) {

        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        Supplier<Integer> visitPassages =
                () -> stageTiming.visit(activeRegistrations, raceTimeZone, limit, visitor);
        return read(visitPassages);
    }

    /** Visits at most {@code limit} ranked teams at one location. */
    public int visitTeamRanking(
            LocationId locationId,
            ZoneId raceTimeZone,
            int limit,
            Consumer<TeamRank> visitor) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        Supplier<Integer> visitRanking =
                () -> teamRanking.visit(locationId, raceTimeZone, limit, visitor);
        return read(visitRanking);
    }

    /** Passes LogBook availability changes to MainTask. */
    private void onLogBookStatus(LogBookStatus status) {

        failIfInvalidArgument(status != null, "status must not be null");
        mainTask.statusChanged(status);
    }

    /** Wakes DataTask after a committed TimingData change. */
    private void onTimingDataChanged(TimingData data) {

        failIfInvalidArgument(data != null, "TimingData must not be null");
        dataTask.dataChanged();
    }

    /** Runs one ordered service read after DataTask has caught up. */
    private <T> T read(Supplier<T> operation) {

        return NodeServiceQuery.execute(
                taskRunner,
                QUERY_TIMEOUT,
                dataTask.caughtUp(),
                operation);
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
                    dataTask.loadAll();
                    break;
                case ERROR:
                    dataTask.unavailable();
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
