package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

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

    /** Creates the Task Lane service around explicitly composed passive state. */
    public NodeService(NodeId nodeId, SerialLane serialLane, NodeServiceState state) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(serialLane != null, "serialLane must not be null");
        failIfInvalidArgument(state != null, "state must not be null");

        this.nodeId = nodeId;
        this.taskLane = new NodeTaskLane(serialLane, TASK_QUERY_TIMEOUT_MILLIS);
        this.state = state;
        this.stageTiming = new StageTiming(
                state.eventData(), state.teamAssignments(), state.stageStartTimes());
    }

    /**
     * Starts the Task Lane execution context.
     *
     * <p>Activation does not wait for LogBook data. Derived timing becomes
     * available only after transient startup history has rebuilt compact derived state.</p>
     */
    @Override
    protected void onActivate() {

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
        return taskLane.submitAndWait(() -> buildStageTiming(raceTimeZone), "stageTiming");
    }

    /** Builds the derived stage ranking while the Task Lane owns mutable derived state. */
    private List<StageTime> buildStageTiming(ZoneId raceTimeZone) {

        taskLane.requireCurrentThread("stageTiming");
        return stageTiming.ranking(state.activeRegistrations(), raceTimeZone);
    }

    /**
     * Receives transient immutable startup history from the LogBook Data Lane.
     *
     * <p>{@link TimingNode} installs this composition-time event link. The
     * handler only queues the history fold onto the Task Lane and returns.</p>
     */
    private void onInitialTimingDataLoaded(List<TimingData> loadedData) {

        // Runnable defers the one-time history fold until the Task Lane owns derived state.
        Runnable rebuildInitialState = () -> rebuildDerivedTiming(loadedData);
        handoffTimingFact(rebuildInitialState, "initial TimingData");
    }

    /**
     * Receives one committed TimingData fact from the LogBook Data Lane.
     *
     * <p>{@link TimingNode} installs this composition-time event link. The
     * handler only queues the derived-state update onto the Task Lane and returns.</p>
     */
    private void onTimingDataCommitted(TimingData data) {

        // Runnable defers the compact derived-state update to preserve Task Lane order.
        Runnable applyCommittedData = () -> applyCommittedTimingData(data);
        handoffTimingFact(applyCommittedData, "committed TimingData");
    }

    /**
     * Hands one immutable TimingData fact from the Data Lane to this service.
     *
     * <p>The handoff is non-blocking because both logical lanes may share one
     * physical worker. Failure to queue the fact makes derived timing state
     * explicitly stale rather than silently losing synchronization.</p>
     */
    private void handoffTimingFact(Runnable operation, String fact) {

        Runnable queueTimingFact = () -> taskLane.execute(operation);
        Consumer<RejectedExecutionException> onRejected = ex -> {
            state.markDerivedTimingStale();
            LOG.error(
                    "Task Lane rejected {} for node {}; derived timing state is stale",
                    fact,
                    nodeId.value(),
                    ex);
        };

        SafeExecute.runOrHandleRejection(queueTimingFact, onRejected);
    }

    /** Rebuilds derived timing while this service owns the SerialLane turn. */
    private void rebuildDerivedTiming(Iterable<TimingData> loadedData) {

        taskLane.requireCurrentThread("rebuildDerivedTiming");
        state.rebuildDerivedTiming(loadedData);
    }

    /** Applies one newly committed TimingData fact during the owned SerialLane turn. */
    private void applyCommittedTimingData(TimingData data) {

        taskLane.requireCurrentThread("applyCommittedTimingData");
        state.applyCommittedTimingData(data);
    }
}
