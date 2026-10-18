package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
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
 * <p>All mutable state is processed in TimingNode Task Lane order. LogBook
 * history initializes the service and later committed TimingData updates it
 * incrementally.</p>
 */
public final class NodeService extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeService.class);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2L);

    /** Initial LogBook history used to build node-local derived state. */
    public static final EventInput<NodeService, List<TimingData>> INITIAL_TIMING_DATA =
            EventInput.of((service, source) -> source.subscribe(service::initialTimingDataLoaded));

    /** Newly committed TimingData used to update node-local derived state. */
    public static final EventInput<NodeService, TimingData> COMMITTED_TIMING_DATA =
            EventInput.of((service, source) -> source.subscribe(service::timingDataCommitted));

    private final NodeId nodeId;
    private final SerialTaskRunner taskRunner;
    private final NodeServiceState state;
    private final StageTiming stageTiming;

    /** Creates the service on the TimingNode Task Lane. */
    public NodeService(
            NodeId nodeId,
            SerialTaskRunner taskRunner,
            NodeServiceState state) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(state != null, "state must not be null");

        this.nodeId = nodeId;
        this.taskRunner = taskRunner;
        this.state = state;
        stageTiming =
                new StageTiming(
                        state.eventData(),
                        state.teamAssignments(),
                        state.stageStartTimes());
    }

    /** Verifies that TimingNode started the shared Task Lane first. */
    @Override
    protected void onActivate() {
        failIfIllegalState(taskRunner.isRunning(), "TimingNode Task Lane is not running");
    }

    /** Makes derived timing unavailable when the service stops. */
    @Override
    protected void onDeactivate() {
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

    /** Rebuilds derived state from the initially loaded LogBook history. */
    private void initialTimingDataLoaded(List<TimingData> loadedData) {
        update("initial TimingData", () -> state.rebuildDerivedTiming(loadedData));
    }

    /** Applies one newly committed TimingData record. */
    private void timingDataCommitted(TimingData data) {
        update("committed TimingData", () -> state.applyCommittedTimingData(data));
    }

    /** Runs one ordered result-bearing service read. */
    private <T> T read(Supplier<T> operation) {
        return NodeServiceQuery.execute(taskRunner, QUERY_TIMEOUT, operation);
    }

    /** Runs one ordered fire-and-forget service update. */
    private void update(String name, Runnable operation) {
        NodeServiceUpdateTask.execute(
                taskRunner,
                operation,
                failure -> updateFailed(name, failure));
    }

    /** Marks derived state stale when a queued service update fails. */
    private void updateFailed(String name, Throwable failure) {
        state.markDerivedTimingStale();
        LOG.error(
                "NodeService {} failed for node {}; derived timing is stale",
                name,
                nodeId.value(),
                failure);
    }
}
