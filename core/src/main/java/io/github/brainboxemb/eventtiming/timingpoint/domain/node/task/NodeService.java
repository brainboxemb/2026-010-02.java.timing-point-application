package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.RunnableSerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Node-local service for Task Lane-owned RaceData and derived timing.
 *
 * <p>The Task Lane is a bounded logical serial queue, not a dedicated thread:
 * the supplied executor may share Runtime's physical worker with the Data Lane.
 * Mutable Task Lane state lives in {@link NodeServiceState}; {@link NodeTaskLane}
 * owns Task Lane execution/wait mechanics and the service owns semantic orchestration.
 * {@link TimingNode} binds the LogBook's immutable startup-history and committed-data
 * event sources to this service; NodeService owns the internal handlers but has no
 * LogBook lifecycle or storage dependency of its own.</p>
 */
public final class NodeService extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeService.class);
    private static final long TASK_QUERY_TIMEOUT_MILLIS = 2000L;

    /** One immutable startup-history fact queued onto the Task Lane. */
    private static final class InitialTimingDataItem implements NodeTaskLane.Item {
        private final List<TimingData> timingData;

        private InitialTimingDataItem(List<TimingData> timingData) {
            this.timingData = timingData;
        }

        @Override
        public String name() {
            return "initial TimingData";
        }
    }

    /** One newly committed TimingData fact queued onto the Task Lane. */
    private static final class CommittedTimingDataItem implements NodeTaskLane.Item {
        private final TimingData timingData;

        private CommittedTimingDataItem(TimingData timingData) {
            this.timingData = timingData;
        }

        @Override
        public String name() {
            return "committed TimingData";
        }
    }

    /** One ordered stage-timing query queued onto the Task Lane. */
    private static final class StageTimingQueryItem
            implements NodeTaskLane.ResultItem<List<StageTime>> {

        private final ZoneId raceTimeZone;
        private final CompletableFuture<List<StageTime>> result =
                new CompletableFuture<List<StageTime>>();

        private StageTimingQueryItem(ZoneId raceTimeZone) {
            this.raceTimeZone = raceTimeZone;
        }

        @Override
        public String name() {
            return "stageTiming";
        }

        @Override
        public Future<List<StageTime>> result() {
            return result;
        }

        private void complete(List<StageTime> stageTimes) {
            result.complete(stageTimes);
        }

        private void fail(Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    /** Initial LogBook history input used to rebuild derived timing state. */
    public static final EventInput<NodeService, List<TimingData>> INITIAL_TIMING_DATA =
            EventInput.of(
                    (service, source) -> source.subscribe(service::onInitialTimingDataLoaded));

    /** Newly committed LogBook TimingData input used for incremental updates. */
    public static final EventInput<NodeService, TimingData> COMMITTED_TIMING_DATA =
            EventInput.of(
                    (service, source) -> source.subscribe(service::onTimingDataCommitted));

    private final NodeId nodeId;
    private final NodeTaskLane taskLane;
    private final NodeServiceState state;
    private final StageTiming stageTiming;

    private List<TimingData> startupTimingData;

    /** Creates the Task Lane service around explicitly composed passive state. */
    public NodeService(NodeId nodeId, RunnableSerialLane serialLane, NodeServiceState state) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(serialLane != null, "serialLane must not be null");
        failIfInvalidArgument(state != null, "state must not be null");

        this.nodeId = nodeId;
        this.taskLane = new NodeTaskLane(serialLane, TASK_QUERY_TIMEOUT_MILLIS, this::processTaskLaneItem);
        this.state = state;
        this.stageTiming = new StageTiming(
                state.eventData(), state.teamAssignments(), state.stageStartTimes());
    }

    /**
     * Builds startup-derived state and then starts normal Task Lane execution.
     *
     * <p>TimingNode activates LogBook first. Its synchronous recovery publishes
     * the initial TimingData while this service is still inactive, so the
     * startup fold does not need another queued lane turn.</p>
     */
    @Override
    protected void onActivate() {

        List<TimingData> loadedData = startupTimingData;
        startupTimingData = null;
        if (loadedData != null) {
            state.rebuildDerivedTiming(loadedData);
        }
        taskLane.activate();
    }

    /**
     * Stops Task Lane work and marks derived timing unavailable.
     */
    @Override
    protected void onDeactivate() {

        taskLane.deactivate();
        state.markDerivedTimingUnavailable();
    }

    /**
     * Returns the current derived stage ranking.
     *
     * <p>The call waits for ordered Task Lane execution.</p>
     */
    public List<StageTime> stageTiming(ZoneId raceTimeZone) {

        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");

        /*
         * A query already running on this Task Lane has satisfied its ordering
         * requirement and can read owned state directly. External callers queue
         * one named result-bearing query and wait for that ordered result.
         */
        if (taskLane.isExecutingOnCurrentThread()) {
            return buildStageTiming(raceTimeZone);
        }
        return taskLane.submitAndWait(new StageTimingQueryItem(raceTimeZone));
    }

    /** Builds the derived stage ranking while the Task Lane owns mutable derived state. */
    private List<StageTime> buildStageTiming(ZoneId raceTimeZone) {

        taskLane.requireCurrentThread("stageTiming");
        return stageTiming.ranking(state.activeRegistrations(), raceTimeZone);
    }

    /**
     * Receives transient immutable startup history from LogBook recovery.
     *
     * <p>During normal composition this arrives before NodeService activation
     * and is consumed synchronously by {@link #onActivate()}. A later replay
     * still falls back to the ordinary Task Lane handoff.</p>
     */
    private void onInitialTimingDataLoaded(List<TimingData> loadedData) {
        failIfInvalidArgument(loadedData != null, "loadedData must not be null");

        if (isActive()) {
            handoffTimingFact(new InitialTimingDataItem(loadedData));
        } else {
            startupTimingData = loadedData;
        }
    }

    /**
     * Receives one committed TimingData fact from the LogBook Data Lane.
     *
     * <p>{@link TimingNode} installs this composition-time event link. The
     * handler only queues the derived-state update onto the Task Lane and returns.</p>
     */
    private void onTimingDataCommitted(TimingData data) {
        handoffTimingFact(new CommittedTimingDataItem(data));
    }

    /**
     * Hands one immutable TimingData fact from the Data Lane to this service.
     *
     * <p>The handoff is non-blocking because both logical lanes may share one
     * physical worker. Failure to queue the fact makes derived timing state
     * explicitly stale rather than silently losing synchronization.</p>
     */
    private void handoffTimingFact(NodeTaskLane.Item item) {
        try {
            taskLane.execute(item);
        } catch (RuntimeException ex) {
            state.markDerivedTimingStale();
            LOG.error(
                    "Task Lane rejected {} for node {}; derived timing state is stale",
                    item.name(),
                    nodeId.value(),
                    ex);
        }
    }

    /** Processes one typed NodeService item on the Task Lane worker. */
    private void processTaskLaneItem(NodeTaskLane.Item item) {
        try {
            if (item instanceof InitialTimingDataItem) {
                rebuildDerivedTiming(((InitialTimingDataItem) item).timingData);
            } else if (item instanceof CommittedTimingDataItem) {
                applyCommittedTimingData(((CommittedTimingDataItem) item).timingData);
            } else if (item instanceof StageTimingQueryItem) {
                StageTimingQueryItem query = (StageTimingQueryItem) item;
                query.complete(buildStageTiming(query.raceTimeZone));
            } else {
                throw new IllegalStateException(
                        "Unsupported NodeService Task Lane item " + item.getClass().getName());
            }
        } catch (RuntimeException ex) {
            if (item instanceof StageTimingQueryItem) {
                ((StageTimingQueryItem) item).fail(ex);
            }
            throw ex;
        } catch (Error error) {
            if (item instanceof StageTimingQueryItem) {
                ((StageTimingQueryItem) item).fail(error);
            }
            throw error;
        }
    }

    /** Rebuilds derived timing while this service owns the RunnableSerialLane turn. */
    private void rebuildDerivedTiming(Iterable<TimingData> loadedData) {

        taskLane.requireCurrentThread("rebuildDerivedTiming");
        state.rebuildDerivedTiming(loadedData);
    }

    /** Applies one newly committed TimingData fact during the owned RunnableSerialLane turn. */
    private void applyCommittedTimingData(TimingData data) {

        taskLane.requireCurrentThread("applyCommittedTimingData");
        state.applyCommittedTimingData(data);
    }
}
