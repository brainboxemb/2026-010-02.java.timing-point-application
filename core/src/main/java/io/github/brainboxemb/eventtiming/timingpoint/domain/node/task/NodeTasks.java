package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.UniqueTeamRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamTaskState;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

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

/**
 * Owns one TimingNode's Task Lane, live RaceData and derived timing state.
 *
 * <p>The Task Lane is a bounded logical serial queue, not a dedicated thread:
 * the supplied executor may share Runtime's physical worker with the Data Lane.
 * Mutable TeamAssignments, StageStartTimes and UniqueTeamRegistrations never
 * leave this lane. The Data Lane supplies only immutable initial-loaded/committed
 * TimingData facts and bounded LogBook reads.</p>
 */
public final class NodeTasks implements UpstreamTaskState {
    private static final Logger LOG =
            LoggerFactory.getLogger(NodeTasks.class);
    private static final long TASK_QUERY_TIMEOUT_MILLIS = 2000L;

    private final LogBook logBook;
    private final SerialLane taskLane;
    private final RaceDataState raceData;
    private final UniqueTeamRegistrations uniqueTeamRegistrations =
            new UniqueTeamRegistrations();
    private final StageTiming stageTiming;
    private final List<TimingData> committedTimingData =
            new ArrayList<TimingData>();
    private volatile boolean derivedTimingReady;
    private volatile boolean derivedTimingStale;

    private final UpstreamHandler upstreamHandler;

    public NodeTasks(
            LogBook logBook,
            SerialLane taskLane,
            EventData eventData,
            UpstreamStream upstreamStream) {
        if (logBook == null) {
            throw new IllegalArgumentException("logBook must not be null");
        }
        if (taskLane == null) {
            throw new IllegalArgumentException("taskLane must not be null");
        }
        if (eventData == null) {
            throw new IllegalArgumentException("eventData must not be null");
        }
        if (upstreamStream == null) {
            throw new IllegalArgumentException("upstreamStream must not be null");
        }

        this.logBook = logBook;
        this.taskLane = taskLane;
        this.raceData = new RaceDataState(eventData);
        this.stageTiming =
                new StageTiming(
                        eventData,
                        raceData.teamAssignments(),
                        raceData.stageStartTimes());

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
                        this);
    }

    public void activate() {
        taskLane.start();
    }

    public void deactivate() {
        taskLane.close();
        derivedTimingReady = false;
    }

    /** Returns the Task Lane-owned upstream protocol boundary for this node. */
    public UpstreamHandler upstreamHandler() {
        return upstreamHandler;
    }

    /** Returns the current derived stage ranking through the Task Lane. */
    public List<StageTime> stageTiming(ZoneId raceTimeZone) {
        if (raceTimeZone == null) {
            throw new IllegalArgumentException("raceTimeZone must not be null");
        }
        return executeOnTaskLaneAndWait(
                () -> stageTimingOnTaskLane(raceTimeZone),
                "stageTiming");
    }

    private List<StageTime> stageTimingOnTaskLane(ZoneId raceTimeZone) {
        requireTaskLane("stageTiming");
        ensureDerivedTimingAvailable();
        return stageTiming.ranking(
                new ArrayList<TimingData>(committedTimingData),
                raceTimeZone);
    }

    @Override
    public void applyRaceData(UpstreamData.RaceData changes) {
        requireTaskLane("applyRaceData");
        raceData.applyChanges(changes);
    }

    @Override
    public TeamId teamIdFor(RegistrationId registrationId) {
        requireTaskLane("teamIdFor");
        return raceData.teamIdFor(registrationId);
    }

    @Override
    public TimingTimestamp stageStartTimeFor(TeamId teamId) {
        requireTaskLane("stageStartTimeFor");
        return raceData.stageStartTimeFor(teamId);
    }

    @Override
    public int uniqueTeamRegistrationCount() {
        requireTaskLane("uniqueTeamRegistrationCount");
        ensureDerivedTimingAvailable();
        return uniqueTeamRegistrations.uniqueTeamCount(raceData::teamIdFor);
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
                () -> rebuildDerivedTiming(snapshot),
                "initial TimingData");
    }

    private void onTimingDataCommitted(TimingData data) {
        queueTimingFactOnTaskLane(
                () -> applyCommittedTimingData(data),
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

        derivedTimingStale = true;
        LOG.error(
                "Task Lane could not queue {} for node {}; queueResult={}, derived timing state is stale",
                fact,
                logBook.timingNodeId().value(),
                queueResult);
    }

    private void rebuildDerivedTiming(List<TimingData> loadedData) {
        requireTaskLane("rebuildDerivedTiming");
        committedTimingData.clear();
        uniqueTeamRegistrations.reset();

        for (TimingData data : loadedData) {
            committedTimingData.add(data);
            updateUniqueTeamRegistrations(data);
        }

        derivedTimingStale = false;
        derivedTimingReady = true;
    }

    private void applyCommittedTimingData(TimingData data) {
        requireTaskLane("applyCommittedTimingData");
        if (!derivedTimingReady) {
            derivedTimingStale = true;
            return;
        }

        committedTimingData.add(data);
        updateUniqueTeamRegistrations(data);
    }

    private void updateUniqueTeamRegistrations(TimingData data) {
        final RegistrationId registrationId;
        final TimingData.RegistrationAction action;

        if (data instanceof TimingData.AutomaticRegistration) {
            TimingData.AutomaticRegistration registration =
                    (TimingData.AutomaticRegistration) data;
            registrationId = registration.registrationId();
            action = registration.action();
        } else if (data instanceof TimingData.ManualRegistration) {
            TimingData.ManualRegistration registration =
                    (TimingData.ManualRegistration) data;
            registrationId = registration.registrationId();
            action = registration.action();
        } else {
            return;
        }

        if (action == TimingData.RegistrationAction.ADD) {
            uniqueTeamRegistrations.registered(registrationId);
        } else if (action == TimingData.RegistrationAction.REV) {
            uniqueTeamRegistrations.revoked(registrationId);
        }
    }

    private void ensureDerivedTimingAvailable() {
        if (!derivedTimingReady || derivedTimingStale) {
            throw new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    "Task Lane derived timing state is not available");
        }
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
