package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
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
 * <p>The LogBook publishes only its committed TimingData head. NodeService
 * chooses how to read that history and keeps its derived state caught up in
 * bounded Task Lane batches.</p>
 */
public final class NodeService extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeService.class);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2L);

    /** Published LogBook head used to catch derived state up to committed history. */
    public static final EventInput<NodeService, Long> TIMING_DATA_HEAD_CHANGED =
            EventInput.of(
                    (service, source) ->
                            source.subscribe(service::timingDataHeadChanged));

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

    /** Wakes bounded LogBook catch-up after recovery or a later commit. */
    private void timingDataHeadChanged(Long head) {
        failIfInvalidArgument(head != null, "TimingData head must not be null");
        historyTask.headChanged(head.longValue(), taskRunner);
    }

    /** Runs one ordered result-bearing service read. */
    private <T> T read(Supplier<T> operation) {
        return NodeServiceQuery.execute(taskRunner, QUERY_TIMEOUT, operation);
    }

    /** Reports failed LogBook catch-up and leaves derived state stale. */
    private void historyFailed(Throwable failure) {
        LOG.error(
                "NodeService TimingData catch-up failed for node {}; derived timing is stale",
                nodeId.value(),
                failure);
    }
}
