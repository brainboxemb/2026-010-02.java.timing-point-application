package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Node-local service for Task Lane-owned RaceData and derived timing.
 *
 * <p>The Task Lane is a bounded logical serial queue, not a dedicated thread:
 * the supplied executor may share Runtime's physical worker with the Data Lane.
 * Mutable Task Lane state lives in {@link NodeServiceState}; the service itself
 * owns lifecycle, queueing and orchestration. {@link TimingNode} wires the
 * LogBook's immutable startup-history and committed-data events to this service;
 * NodeService has no LogBook lifecycle or storage dependency of its own.</p>
 */
public final class NodeService {
    private static final Logger LOG = LoggerFactory.getLogger(NodeService.class);
    private static final long TASK_QUERY_TIMEOUT_MILLIS = 2000L;

    private final NodeId nodeId;
    private final SerialLane serialLane;
    private final NodeServiceState state;
    private final StageTiming stageTiming;

    /** Creates the Task Lane service around explicitly composed passive state. */
    public NodeService(NodeId nodeId, SerialLane serialLane, NodeServiceState state) {
        checkArgument(nodeId != null, "nodeId must not be null");
        checkArgument(serialLane != null, "serialLane must not be null");
        checkArgument(state != null, "state must not be null");

        this.nodeId = nodeId;
        this.serialLane = serialLane;
        this.state = state;
        this.stageTiming = new StageTiming(state.eventData(), state.teamAssignments(), state.stageStartTimes());
    }

    /**
     * Starts the Task Lane execution context.
     *
     * <p>Activation does not wait for LogBook data. Derived timing becomes
     * available only after transient startup history has rebuilt compact derived state.</p>
     */
    public void activate() {
        serialLane.start();
    }

    /**
     * Stops Task Lane work and marks derived timing unavailable.
     */
    public void deactivate() {
        serialLane.close();
        state.markDerivedTimingUnavailable();
    }

    /**
     * Returns the current derived stage ranking.
     *
     * <p>The call waits for ordered Task Lane execution.</p>
     */
    public List<StageTime> stageTiming(ZoneId raceTimeZone) {
        checkArgument(raceTimeZone != null, "raceTimeZone must not be null");

        /*
         * A query already running on this Task Lane has satisfied its ordering
         * requirement and can read owned state directly. External callers queue
         * one named result-bearing query and wait for that ordered result.
         */
        if (serialLane.isExecutingOnCurrentThread()) {
            return buildStageTiming(raceTimeZone);
        }
        Callable<List<StageTime>> rankingQuery = () -> buildStageTiming(raceTimeZone);
        return submitAndWait(rankingQuery, "stageTiming");
    }

    /** Builds the derived stage ranking while the Task Lane owns mutable derived state. */
    private List<StageTime> buildStageTiming(ZoneId raceTimeZone) {
        requireTaskLane("stageTiming");
        return stageTiming.ranking(state.activeRegistrations(), raceTimeZone);
    }

    /**
     * Receives transient immutable startup history from the LogBook Data Lane.
     *
     * <p>{@link TimingNode} installs this composition-time event link. The
     * handler only queues the history fold onto the Task Lane and returns.</p>
     */
    public void onInitialTimingDataLoaded(List<TimingData> loadedData) {
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
    public void onTimingDataCommitted(TimingData data) {
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
        Runnable queueTimingFact =
                () -> serialLane.execute(operation);
        Consumer<RejectedExecutionException> onRejected =
                ex -> {
                    state.markDerivedTimingStale();
                    LOG.error(
                            "Task Lane rejected {} for node {}; derived timing state is stale",
                            fact,
                            nodeId.value(),
                            ex);
                };

        SafeExecute.runOrHandleRejection(
                queueTimingFact,
                onRejected);
    }

    /** Rebuilds derived timing while this service owns the SerialLane turn. */
    private void rebuildDerivedTiming(Iterable<TimingData> loadedData) {
        requireTaskLane("rebuildDerivedTiming");
        state.rebuildDerivedTiming(loadedData);
    }

    /** Applies one newly committed TimingData fact during the owned SerialLane turn. */
    private void applyCommittedTimingData(TimingData data) {
        requireTaskLane("applyCommittedTimingData");
        state.applyCommittedTimingData(data);
    }

    /** Fails fast when a helper that owns mutable Task Lane state runs outside that lane. */
    private void requireTaskLane(String operation) {
        if (!serialLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(operation + " requires the TimingNode Task Lane");
        }
    }

    /**
     * Submits one result-bearing operation and waits for its ordered result.
     *
     * <p>Callers already executing on the SerialLane must use their direct
     * read path instead of self-submitting and waiting.</p>
     */
    private <R> R submitAndWait(Callable<R> work, String operation) {
        final Future<R> future;
        try {
            future = serialLane.submit(work);
        } catch (RejectedExecutionException ex) {
            throw rejectedOperation(operation, ex);
        }
        return await(future, operation);
    }

    /** Converts Future completion into the stable Task Lane operation exception contract. */
    private <R> R await(Future<R> future, String operation) {
        try {
            return future.get(TASK_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new OperationException(
                    OperationException.Reason.TIMEOUT,
                    operation + " timed out on the TimingNode Task Lane",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OperationException(
                    OperationException.Reason.INTERRUPTED,
                    operation + " was interrupted on the TimingNode Task Lane",
                    ex);
        } catch (CancellationException ex) {
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " was cancelled on the TimingNode Task Lane",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " failed on the TimingNode Task Lane",
                    cause);
        }
    }

    /** Maps standard Task Lane rejection to the result-bearing domain operation categories. */
    private OperationException rejectedOperation(String operation, RejectedExecutionException rejection) {
        if (serialLane.state() == SerialLane.State.FAILED) {
            return new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " could not run because the TimingNode Task Lane failed",
                    serialLane.failure() == null ? rejection : serialLane.failure());
        }
        if (serialLane.state() != SerialLane.State.RUNNING) {
            return new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    operation + " could not be queued because the TimingNode Task Lane is not running",
                    rejection);
        }
        return new OperationException(
                OperationException.Reason.BUSY,
                operation + " could not be queued because the TimingNode Task Lane is full",
                rejection);
    }


}
