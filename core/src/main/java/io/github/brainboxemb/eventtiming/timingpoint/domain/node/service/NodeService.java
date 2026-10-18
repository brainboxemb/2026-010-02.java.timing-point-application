package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookRecoveryStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Coordinates node-local Task Lane data.
 *
 * <p>Events only wake the service. MainTask reads the LogBook head and fetches missing
 * TimingData itself in bounded batches. The individual data objects own their state.</p>
 */
public final class NodeService extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeService.class);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2L);

    /** Completed LogBook recovery signal. */
    public static final EventInput<NodeService, LogBookRecoveryStatus> LOGBOOK_RECOVERED =
            EventInput.of((service, source) -> source.subscribe(service::onLogBookRecovered));

    /** Post-commit signal; the TimingData payload is deliberately not consumed by NodeService. */
    public static final EventInput<NodeService, TimingData> TIMING_DATA_CHANGED =
            EventInput.of((service, source) -> source.subscribe(service::onTimingDataChanged));

    private final NodeId nodeId;
    private final SerialTaskRunner taskRunner;
    private final RaceDataState raceData;
    private final ActiveRegistrations activeRegistrations;
    private final StageTiming stageTiming;
    private final MainTask mainTask;

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
        mainTask = new MainTask(logBook);
    }

    /** Verifies that TimingNode started the shared Task Lane first. */
    @Override
    protected void onActivate() {

        failIfIllegalState(taskRunner.isRunning(), "TimingNode Task Lane is not running");
    }

    /** Stops service catch-up. */
    @Override
    protected void onDeactivate() {

        mainTask.stop();
    }

    /**
     * Applies one ordered Central RACE_DATA change set.
     *
     * <p>UpstreamHandler calls this while the node Task Lane is current. Team assignment
     * changes are then reflected immediately in active-registration counts.</p>
     */
    public void applyRaceData(UpstreamData.RaceData changes) {

        failIfInvalidArgument(changes != null, "changes must not be null");

        raceData.applyChanges(changes);
        for (UpstreamData.TeamAssignmentChange change : changes.teamAssignmentChanges()) {
            RegistrationId registrationId = change.registrationId();
            activeRegistrations.teamAssignmentChanged(registrationId, raceData.teamIdFor(registrationId));
        }
    }

    /** Returns the number of uniquely registered teams at one location. */
    public int uniqueTeamRegistrationCount(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");

        Supplier<Integer> readCount = () -> activeRegistrations.uniqueTeamCount(locationId);
        return read(readCount);
    }

    /**
     * Visits at most {@code limit} fastest stage times in ranking order.
     *
     * <p>This call waits for ordered Task Lane execution. The visitor runs on the Task Lane
     * and must remain short and non-blocking.</p>
     *
     * @return number of visited rows
     */
    public int visitStageTiming(ZoneId raceTimeZone, int limit, Consumer<StageTime> visitor) {

        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        Supplier<Integer> visitRanking =
                () -> stageTiming.visitRanking(activeRegistrations, raceTimeZone, limit, visitor);
        return read(visitRanking);
    }

    /** Wakes MainTask after successful LogBook startup recovery. */
    private void onLogBookRecovered(LogBookRecoveryStatus recovery) {

        failIfInvalidArgument(recovery != null, "recovery must not be null");

        if (recovery.failed()) {
            mainTask.logBookFailed(recovery.failureMessage());
        } else {
            mainTask.logBookReady();
        }
    }

    /** Wakes MainTask after a commit; the event payload is intentionally ignored. */
    private void onTimingDataChanged(TimingData ignored) {

        failIfInvalidArgument(ignored != null, "TimingData must not be null");
        mainTask.timingDataChanged();
    }

    /** Runs one ordered service read after MainTask has caught up with signalled LogBook changes. */
    private <T> T read(Supplier<T> operation) {

        return NodeServiceQuery.execute(taskRunner, QUERY_TIMEOUT, mainTask.catchUpCompletion(), operation);
    }

    /** Applies one committed TimingData record to the interested Task Lane data objects. */
    private void applyTimingData(TimingData data) {

        activeRegistrations.apply(data, raceData::teamIdFor);
    }

    /** Logs a failed MainTask catch-up. */
    private void catchUpFailed(Throwable failure) {

        LOG.error("NodeService TimingData catch-up failed for node {}", nodeId.value(), failure);
    }

    /**
     * Keeps Task Lane data caught up with committed LogBook TimingData.
     *
     * <p>The task owns only its source cursor and one pending bounded LogBook read. Wake
     * requests coalesce while a run is active.</p>
     */
    private final class MainTask extends AbstractTask {
        private static final int TIMING_DATA_BATCH_SIZE = 64;

        private final LogBookAccessor logBook;

        private boolean logBookReady;
        private boolean rebuild;
        private long nextSequence = 1L;
        private CompletableFuture<List<TimingData>> pendingRead;
        private volatile CompletableFuture<Void> catchUpCompletion = new CompletableFuture<Void>();

        private MainTask(LogBookAccessor logBook) {

            this.logBook = logBook;
        }

        /** Marks the LogBook ready and schedules a complete initial catch-up. */
        private synchronized void logBookReady() {

            logBookReady = true;
            rebuild = true;
            nextSequence = 1L;
            beginCatchUp();
            wake(taskRunner);
        }

        /** Fails pending service reads when LogBook recovery failed. */
        private synchronized void logBookFailed(String message) {

            failIfInvalidArgument(message != null && !message.trim().isEmpty(), "message must not be blank");
            catchUpCompletion.completeExceptionally(
                    new IllegalStateException("LogBook recovery failed: " + message.trim()));
        }

        /** Schedules catch-up after newly committed TimingData. */
        private synchronized void timingDataChanged() {

            if (logBookReady) {
                beginCatchUp();
                wake(taskRunner);
            }
        }

        /** Returns completion of all LogBook changes signalled so far. */
        private CompletionStage<Void> catchUpCompletion() {

            return catchUpCompletion;
        }

        /** Cancels catch-up and resets the source cursor for later activation. */
        private synchronized void stop() {

            cancel();
            logBookReady = false;
            rebuild = false;
            nextSequence = 1L;
            pendingRead = null;
            catchUpCompletion.cancel(true);
            catchUpCompletion = new CompletableFuture<Void>();
        }

        /** Preserves the source cursor while clearing one-run asynchronous state. */
        @Override
        protected void resetForRun() {

            pendingRead = null;
        }

        /** Reads and applies missing TimingData until the current LogBook head is reached. */
        @Override
        public TaskStep runStep() {

            if (rebuild) {
                activeRegistrations.reset();
                rebuild = false;
            }

            if (pendingRead != null) {
                applyCompletedRead();
            }

            long head =
                    logBook.process(
                            LogBookQueries.timingDataHead(),
                            LogBookQuery.ReadConsistency.CURRENT);
            if (nextSequence <= head) {
                submitNextRead(head);
                return TaskStep.await(pendingRead);
            }

            catchUpCompletion.complete(null);
            return TaskStep.done();
        }

        /** Fails pending service reads if one catch-up run fails. */
        @Override
        protected void onRunCompleted(Throwable failure) {

            pendingRead = null;
            if (failure != null) {
                catchUpCompletion.completeExceptionally(failure);
                catchUpFailed(failure);
            }
        }

        /** Starts a bounded ordered LogBook read from the current source cursor. */
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
                applyTimingData(data);
                nextSequence++;
            }
        }

        /** Starts a new completion window when the service was previously caught up. */
        private void beginCatchUp() {

            if (catchUpCompletion.isDone()) {
                catchUpCompletion = new CompletableFuture<Void>();
            }
        }
    }
}
