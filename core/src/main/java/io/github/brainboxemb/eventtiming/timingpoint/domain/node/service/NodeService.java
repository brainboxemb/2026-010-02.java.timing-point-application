package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.RegisteredTeams;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Coordinates node-local state that must remain ordered on the TimingNode Task Lane.
 *
 * <p>MainTask reacts only to LogBook availability. DataTask owns the active-WP LogBook cursor:
 * while CLOSED there is no registration projection; OPEN(locationId) rebuilds that WP from the
 * complete LogBook in bounded batches and then keeps it caught up. RaceDataState owns live Central
 * RACE_DATA. ActiveRegistrations and RegisteredTeams own the two active-WP projections.</p>
 *
 * <p>The public registration reads are relative to the one currently open WP, so they take no
 * LocationId argument. Reads first wait for DataTask reconstruction/catch-up and then execute on
 * the Task Lane. Calling them while CLOSED is an illegal state.</p>
 */
public final class NodeService extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeService.class);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2L);

    /** LogBook availability status input. */
    public static final EventInput<NodeService, LogBookStatus> LOGBOOK_STATUS =
            EventInput.of((service, source) -> source.subscribe(service::onLogBookStatus));

    /** Post-commit signal; DataTask reads the authoritative timing record from LogBook. */
    public static final EventInput<NodeService, TimingRecord> TIMING_DATA_CHANGED =
            EventInput.of((service, source) -> source.subscribe(service::onTimingDataChanged));

    private final NodeId nodeId;
    private final SerialTaskRunner taskRunner;
    private final TimeSource timeSource;
    private final EventData eventData;
    private final RaceDataState raceData;
    private final ActiveRegistrations activeRegistrations;
    private final RegisteredTeams registeredTeams = new RegisteredTeams();
    private final DataTask dataTask;
    private final MainTask mainTask = new MainTask();

    /** Incremental team membership changes for a smart display's own team list. */
    public final EventSource<RegisteredTeams.Change> REGISTERED_TEAMS_CHANGED = registeredTeams.CHANGED;

    /** Creates the service over one LogBook and the node Task Lane. */
    public NodeService(
            NodeId nodeId,
            SerialTaskRunner taskRunner,
            LogBookAccessor logBook,
            TimeSource timeSource,
            EventData eventData,
            RaceDataState raceData,
            ActiveRegistrations activeRegistrations) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");
        failIfInvalidArgument(eventData != null, "eventData must not be null");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        failIfInvalidArgument(activeRegistrations != null, "activeRegistrations must not be null");

        this.nodeId = nodeId;
        this.taskRunner = taskRunner;
        this.timeSource = timeSource;
        this.eventData = eventData;
        this.raceData = raceData;
        this.activeRegistrations = activeRegistrations;
        dataTask =
                new DataTask(
                        taskRunner,
                        logBook,
                        raceData,
                        activeRegistrations,
                        registeredTeams,
                        this::dataTaskRebuilt,
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
     * <p>UpstreamHandler calls this while the node Task Lane is current. The shared
     * TimeSource is sampled once so stage-start boundary values are normalized to UTC
     * before entering RaceDataState. Team assignment changes are reflected in RegisteredTeams.</p>
     */
    public void applyRaceData(UpstreamData.RaceData changes) {

        failIfInvalidArgument(changes != null, "changes must not be null");

        raceData.applyChanges(changes, timeSource.now());
        for (UpstreamData.TeamAssignmentChange change : changes.teamAssignmentChanges()) {
            RegistrationId registrationId = change.registrationId();
            TeamId teamId = raceData.teamIdFor(registrationId);
            dataTask.teamAssignmentChanged(registrationId, teamId);
        }
    }

    /** Requests a complete registration-state rebuild when TimingNode opens one WP. */
    public void locationOpened(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        dataTask.locationOpened(locationId);
    }

    /** Deactivates registration state when TimingNode closes its WP. */
    public void locationClosed() {

        dataTask.locationClosed();
    }

    /** Resolves fixed event reference data for one location/team pair. */
    public RegistrationId registrationIdFor(
            LocationId locationId,
            TeamId teamId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(teamId != null, "teamId must not be null");
        return eventData.registrationIdFor(locationId, teamId);
    }

    /** Returns the number of uniquely registered teams in the currently open WP. */
    public int uniqueTeamRegistrationCount() {

        return readActive(registeredTeams::uniqueTeamCount);
    }

    /**
     * Visits registrations of one WP in original ADD order, including invalidated rows.
     *
     * <p>This is the maintained chronological registration projection used by the smart-display
     * boundary; it is not a calculated presentation list.</p>
     */
    public int visitRegistrations(
            int limit, Consumer<ActiveRegistrations.Registration> visitor) {

        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        return readActive(() -> activeRegistrations.visit(limit, visitor));
    }

    /**
     * Visits later registration rows without restarting at the first displayed sequence.
     *
     * <p>A later REV can change an earlier row. Use visitRegistrations when checking
     * those earlier rows for invalidation; this cursor only pages newly added rows.</p>
     */
    public int visitRegistrationsAfter(
            long afterSequence,
            int limit,
            Consumer<ActiveRegistrations.Registration> visitor) {

        failIfInvalidArgument(afterSequence >= 0L, "afterSequence must not be negative");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        return readActive(() -> activeRegistrations.visitAfter(afterSequence, limit, visitor));
    }

    /** Visits the complete current unique-team set for an order-independent compact display burst. */
    public int visitRegisteredTeams(Consumer<TeamId> visitor) {

        failIfInvalidArgument(visitor != null, "visitor must not be null");

        return readActive(() -> registeredTeams.visit(visitor));
    }

    /** Passes LogBook availability changes to MainTask. */
    private void onLogBookStatus(LogBookStatus status) {

        failIfInvalidArgument(status != null, "status must not be null");
        mainTask.statusChanged(status);
    }

    /** Wakes DataTask after a committed timing-record change. */
    private void onTimingDataChanged(TimingRecord record) {

        failIfInvalidArgument(record != null, "TimingRecord must not be null");
        dataTask.dataChanged();
    }

    /** Runs one ordered active-WP read after DataTask has reconstructed/caught up. */
    private <T> T readActive(Supplier<T> operation) {

        return read(
                () -> {
                    dataTask.requireActive();
                    return operation.get();
                });
    }

    /** Runs one ordered service read after DataTask has caught up. */
    private <T> T read(Supplier<T> operation) {

        return NodeServiceQuery.execute(
                taskRunner,
                QUERY_TIMEOUT,
                dataTask.caughtUp(),
                operation);
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
                    dataTask.logBookAvailable();
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
