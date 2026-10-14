package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Node-local service for Task Lane-owned RaceData, derived timing and upstream operations.
 *
 * <p>The Task Lane is a bounded logical serial queue, not a dedicated thread:
 * the supplied executor may share Runtime's physical worker with the Data Lane.
 * Mutable Task Lane state lives in {@link NodeServiceState}; the service itself
 * owns lifecycle, queueing and orchestration. The Data Lane supplies only
 * immutable initial-loaded/committed TimingData facts and bounded LogBook reads.</p>
 */
public final class NodeService {
    private static final Logger LOG =
            LoggerFactory.getLogger(NodeService.class);
    private static final long TASK_QUERY_TIMEOUT_MILLIS = 2000L;

    private final LogBook logBook;
    private final SerialLane serialLane;
    private final NodeServiceState state;
    private final StageTiming stageTiming;


    /** Creates the Task Lane service around explicitly composed passive state. */
    public NodeService(
            LogBook logBook,
            SerialLane serialLane,
            NodeServiceState state) {
        checkArgument(logBook != null, "logBook must not be null");
        checkArgument(serialLane != null, "serialLane must not be null");
        checkArgument(state != null, "state must not be null");

        this.logBook = logBook;
        this.serialLane = serialLane;
        this.state = state;
        this.stageTiming =
                new StageTiming(
                        state.eventData(),
                        state.teamAssignments(),
                        state.stageStartTimes());

        /*
         * Composition-time wiring. Event delivery itself is synchronous on the
         * Data Lane; these listeners only perform a bounded Task Lane handoff.
         */
        logBook.initialDataLoadedEvent()
                .subscribe(this::onInitialTimingDataLoaded);
        logBook.newDataEntryEvent()
                .subscribe(this::onTimingDataCommitted);

    }

    /**
     * Starts the Task Lane execution context.
     *
     * <p>Activation does not wait for LogBook data. Derived timing becomes
     * available only after the initial TimingData snapshot arrives.</p>
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
        return executeOnTaskLaneAndWait(
                () -> stageTimingOnTaskLane(raceTimeZone),
                "stageTiming");
    }

    /** Builds the derived stage ranking while the Task Lane owns mutable derived state. */
    private List<StageTime> stageTimingOnTaskLane(ZoneId raceTimeZone) {
        requireTaskLane("stageTiming");
        return stageTiming.ranking(
                state.committedTimingDataSnapshot(),
                raceTimeZone);
    }

    /** Hands the immutable startup history from the Data Lane to Task Lane-derived state. */
    private void onInitialTimingDataLoaded(List<TimingData> loadedData) {
        List<TimingData> snapshot = new ArrayList<TimingData>(loadedData);
        queueTimingFactOnTaskLane(
                () -> rebuildDerivedTimingOnTaskLane(snapshot),
                "initial TimingData");
    }

    /** Hands one committed TimingData fact from the Data Lane to Task Lane-derived state. */
    private void onTimingDataCommitted(TimingData data) {
        queueTimingFactOnTaskLane(
                () -> applyCommittedTimingDataOnTaskLane(data),
                "committed TimingData");
    }

    /**
     * Queues one immutable TimingData fact from the Data Lane onto the Task Lane.
     *
     * <p>The handoff is non-blocking because both logical lanes may share one
     * physical worker. Failure to queue the fact makes derived timing state
     * explicitly stale rather than silently losing synchronization.</p>
     */
    private void queueTimingFactOnTaskLane(
            Runnable operation,
            String fact) {
        try {
            serialLane.execute(operation);
        } catch (RejectedExecutionException ex) {
            state.markDerivedTimingStale();
            LOG.error(
                    "Task Lane rejected {} for node {}; derived timing state is stale",
                    fact,
                    logBook.timingNodeId().value(),
                    ex);
        }
    }

    /** Rebuilds Task Lane-derived timing from the immutable startup history. */
    private void rebuildDerivedTimingOnTaskLane(
            List<TimingData> loadedData) {
        requireTaskLane("rebuildDerivedTiming");
        state.rebuildDerivedTiming(loadedData);
    }

    /** Applies one newly committed TimingData fact to Task Lane-derived state. */
    private void applyCommittedTimingDataOnTaskLane(
            TimingData data) {
        requireTaskLane("applyCommittedTimingData");
        state.applyCommittedTimingData(data);
    }

    /** Fails fast when a helper that owns mutable Task Lane state runs outside that lane. */
    private void requireTaskLane(String operation) {
        if (!serialLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    operation + " requires the TimingNode Task Lane");
        }
    }

    /**
     * Executes one result-bearing operation on the Task Lane and waits for it.
     *
     * <p>If already on the Task Lane, the work executes directly to avoid
     * self-queueing and waiting on the same serial lane.</p>
     */
    private <R> R executeOnTaskLaneAndWait(
            Callable<R> work,
            String operation) {
        if (serialLane.isExecutingOnCurrentThread()) {
            try {
                return work.call();
            } catch (RuntimeException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new OperationException(
                        OperationException.Reason.FAILED,
                        operation + " failed on the TimingNode Task Lane",
                        ex);
            }
        }

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
            return future.get(
                    TASK_QUERY_TIMEOUT_MILLIS,
                    TimeUnit.MILLISECONDS);
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
    private OperationException rejectedOperation(
            String operation,
            RejectedExecutionException rejection) {
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
