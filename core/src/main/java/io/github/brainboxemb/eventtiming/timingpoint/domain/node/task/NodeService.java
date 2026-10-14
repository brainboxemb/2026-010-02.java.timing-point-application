package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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
    private final SerialLane taskLane;
    private final NodeServiceState state;
    private final StageTiming stageTiming;

    private final UpstreamHandler upstreamHandler;

    public NodeService(
            LogBook logBook,
            SerialLane taskLane,
            EventData eventData,
            UpstreamStream upstreamStream) {
        checkArgument(logBook != null, "logBook must not be null");
        checkArgument(taskLane != null, "taskLane must not be null");
        checkArgument(eventData != null, "eventData must not be null");
        checkArgument(upstreamStream != null, "upstreamStream must not be null");

        this.logBook = logBook;
        this.taskLane = taskLane;
        this.state = new NodeServiceState(eventData);
        this.stageTiming =
                new StageTiming(
                        eventData,
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

        this.upstreamHandler =
                new UpstreamHandler(
                        logBook.timingNodeId(),
                        logBook::tryQueueOperation,
                        new UpstreamTaskLane(),
                        upstreamStream,
                        logBook,
                        state);
    }

    /**
     * Starts the Task Lane execution context.
     *
     * <p>Activation does not wait for LogBook data. Derived timing becomes
     * available only after the initial TimingData snapshot arrives.</p>
     */
    public void activate() {
        taskLane.start();
    }

    /**
     * Stops Task Lane work and marks derived timing unavailable.
     */
    public void deactivate() {
        taskLane.close();
        state.markDerivedTimingUnavailable();
    }

    /** Returns the Task Lane-owned upstream protocol boundary for this node. */
    public UpstreamHandler upstreamHandler() {
        return upstreamHandler;
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

    private List<StageTime> stageTimingOnTaskLane(ZoneId raceTimeZone) {
        requireTaskLane("stageTiming");
        return stageTiming.ranking(
                state.committedTimingDataSnapshot(),
                raceTimeZone);
    }

    /**
     * Tries to queue fire-and-forget work on the Task Lane without waiting.
     *
     * <p>Returns {@code true} only when the work was queued for later serial
     * execution.</p>
     */
    private boolean tryQueueOnTaskLane(Runnable operation) {
        return taskLane.offer(operation)
                == SerialLane.AdmissionResult.ACCEPTED;
    }

    private void onInitialTimingDataLoaded(List<TimingData> loadedData) {
        List<TimingData> snapshot = new ArrayList<TimingData>(loadedData);
        queueTimingFactOnTaskLane(
                () -> rebuildDerivedTimingOnTaskLane(snapshot),
                "initial TimingData");
    }

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
        SerialLane.AdmissionResult queueResult =
                taskLane.offer(operation);
        if (queueResult == SerialLane.AdmissionResult.ACCEPTED) {
            return;
        }

        state.markDerivedTimingStale();
        LOG.error(
                "Task Lane could not queue {} for node {}; queueResult={}, derived timing state is stale",
                fact,
                logBook.timingNodeId().value(),
                queueResult);
    }

    private void rebuildDerivedTimingOnTaskLane(
            List<TimingData> loadedData) {
        requireTaskLane("rebuildDerivedTiming");
        state.rebuildDerivedTiming(loadedData);
    }

    private void applyCommittedTimingDataOnTaskLane(
            TimingData data) {
        requireTaskLane("applyCommittedTimingData");
        state.applyCommittedTimingData(data);
    }

    private void requireTaskLane(String operation) {
        if (!taskLane.isExecutingOnCurrentThread()) {
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
        if (taskLane.isExecutingOnCurrentThread()) {
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

        SerialLane.SubmitResult<R> queuedWork =
                taskLane.submit(work);
        SerialLane.AdmissionResult queueResult =
                queuedWork.admission();
        switch (queueResult) {
            case FULL:
                throw new OperationException(
                        OperationException.Reason.BUSY,
                        operation
                                + " could not be queued because the TimingNode Task Lane is full");
            case NOT_RUNNING:
                if (taskLane.state() == SerialLane.State.FAILED) {
                    throw new OperationException(
                            OperationException.Reason.FAILED,
                            operation
                                    + " could not run because the TimingNode Task Lane failed",
                            taskLane.failure());
                }
                throw new OperationException(
                        OperationException.Reason.UNAVAILABLE,
                        operation
                                + " could not be queued because the TimingNode Task Lane is not running");
            case ACCEPTED:
                return await(queuedWork.futureResult(), operation);
            default:
                throw new IllegalStateException(
                        "Unsupported Task Lane queue result "
                                + queueResult);
        }
    }

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

    private final class UpstreamTaskLane implements UpstreamHandler.TaskLane {
        @Override
        public boolean tryQueue(Runnable task) {
            return tryQueueOnTaskLane(task);
        }

        @Override
        public <R> R executeAndWait(
                Callable<R> task,
                String operation) {
            return executeOnTaskLaneAndWait(task, operation);
        }
    }
}
