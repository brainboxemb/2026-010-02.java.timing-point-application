package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookRecoveryStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns node-local race data and derived timing state.
 *
 * <p>Recovery receives only the recovered LogBook head. NodeService then reads
 * committed history itself in bounded batches. Later single-record commits are
 * applied incrementally without reading them back from the LogBook.</p>
 */
public final class NodeService extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeService.class);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2L);

    /** Completed LogBook recovery used to start bounded history reconstruction. */
    public static final EventInput<NodeService, LogBookRecoveryStatus> LOGBOOK_RECOVERED =
            EventInput.of(
                    (service, source) ->
                            source.subscribe(service::logBookRecovered));

    /** Newly committed TimingData used for incremental derived-state updates. */
    public static final EventInput<NodeService, TimingData> COMMITTED_TIMING_DATA =
            EventInput.of(
                    (service, source) ->
                            source.subscribe(service::timingDataCommitted));

    private final NodeId nodeId;
    private final SerialTaskRunner taskRunner;
    private final NodeServiceState state;
    private final StageTiming stageTiming;
    private final NodeServiceHistoryTask historyTask;

    /** Creates the service on the TimingNode Task Lane. */
    public NodeService(
            NodeId nodeId,
            SerialTaskRunner taskRunner,
            LogBookAccessor logBook,
            NodeServiceState state) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(state != null, "state must not be null");

        this.nodeId = nodeId;
        this.taskRunner = taskRunner;
        this.state = state;
        stageTiming =
                new StageTiming(
                        state.eventData(),
                        state.teamAssignments(),
                        state.stageStartTimes());
        historyTask =
                new NodeServiceHistoryTask(
                        logBook,
                        state,
                        this::historyFailed);
    }

    /** Verifies that TimingNode started the shared Task Lane first. */
    @Override
    protected void onActivate() {
        failIfIllegalState(taskRunner.isRunning(), "TimingNode Task Lane is not running");
    }

    /** Stops history processing and makes derived timing unavailable. */
    @Override
    protected void onDeactivate() {
        historyTask.stop();
        state.markDerivedTimingUnavailable();
    }

    /** Returns the number of uniquely registered teams at one location. */
    public int uniqueTeamRegistrationCount(LocationId locationId) {
        failIfInvalidArgument(locationId != null, "locationId must not be null");
        return read(() -> state.uniqueTeamRegistrationCount(locationId));
    }

    /** Returns the current derived stage ranking. */
    public List<StageTime> stageTiming(ZoneId raceTimeZone) {
        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");
        return read(() -> stageTiming.ranking(state.activeRegistrations(), raceTimeZone));
    }

    /** Starts bounded reconstruction after successful LogBook recovery. */
    private void logBookRecovered(LogBookRecoveryStatus recovery) {
        failIfInvalidArgument(recovery != null, "recovery must not be null");
        if (!recovery.failed()) {
            historyTask.recover(
                    recovery.timingDataHead(),
                    taskRunner);
        }
    }

    /** Applies one new commit directly unless startup reconstruction is still catching up. */
    private void timingDataCommitted(TimingData data) {
        failIfInvalidArgument(data != null, "data must not be null");

        if (historyTask.isRecovering()) {
            historyTask.extendTo(
                    data.sequenceNumber(),
                    taskRunner);
        } else {
            NodeServiceUpdateTask.execute(
                    taskRunner,
                    () -> state.applyTimingData(data),
                    failure -> updateFailed("committed TimingData", failure));
        }
    }

    /** Runs one ordered service read, waiting cooperatively for startup recovery when needed. */
    private <T> T read(Supplier<T> operation) {
        return NodeServiceQuery.execute(
                taskRunner,
                QUERY_TIMEOUT,
                historyTask.isRecovering()
                        ? historyTask.recoveryCompletion()
                        : null,
                operation);
    }

    /** Reports failed startup history reconstruction. */
    private void historyFailed(Throwable failure) {
        LOG.error(
                "NodeService history recovery failed for node {}",
                nodeId.value(),
                failure);
    }

    /** Marks derived state stale after an incremental update failed. */
    private void updateFailed(
            String operation,
            Throwable failure) {

        state.markDerivedTimingStale();
        LOG.error(
                "NodeService {} failed for node {}; derived timing is stale",
                operation,
                nodeId.value(),
                failure);
    }
}
