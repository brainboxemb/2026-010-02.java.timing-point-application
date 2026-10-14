package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookProcessor;
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
 * Owns Task Lane execution, live RaceData and derived timing state for one node.
 *
 * <p>The Task Lane is a bounded logical serial queue, not a dedicated thread:
 * the supplied executor may share Runtime's physical worker with the Data Lane.
 * Mutable TeamAssignments, StageStartTimes and UniqueTeamRegistrations never
 * leave this lane. The Data Lane supplies only immutable initial-loaded/committed
 * TimingData facts and bounded LogBook reads.</p>
 */
public final class NodeTaskProcessor implements UpstreamTaskState {
    private static final Logger LOG =
            LoggerFactory.getLogger(NodeTaskProcessor.class);
    private static final long TASK_QUERY_TIMEOUT_MILLIS = 2000L;

    private final LogBookProcessor logBookProcessor;
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

    public NodeTaskProcessor(
            LogBookProcessor logBookProcessor,
            SerialLane taskLane,
            EventData eventData,
            UpstreamStream upstreamStream) {
        if (logBookProcessor == null) {
            throw new IllegalArgumentException("logBookProcessor must not be null");
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

        this.logBookProcessor = logBookProcessor;
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
        logBookProcessor.initialDataLoadedEvent()
                .subscribe(this::onInitialTimingDataLoaded);
        logBookProcessor.newDataEntryEvent()
                .subscribe(this::onTimingDataCommitted);

        this.upstreamHandler =
                new UpstreamHandler(
                        logBookProcessor.timingNodeId(),
                        logBookProcessor::offerOperation,
                        new UpstreamTaskLane(),
                        upstreamStream,
                        logBookProcessor,
                        this);
    }

    public void activate() {
        taskLane.start();
    }

    public void deactivate() {
        taskLane.close();
        derivedTimingReady = false;
    }

    public UpstreamHandler upstreamHandler() {
        return upstreamHandler;
    }

    /** Returns the current derived stage ranking on the Task Lane. */
    public List<StageTime> stageTiming(ZoneId raceTimeZone) {
        if (raceTimeZone == null) {
            throw new IllegalArgumentException("raceTimeZone must not be null");
        }
        return callOperation(
                () -> {
                    ensureDerivedTimingAvailable();
                    return stageTiming.ranking(
                            new ArrayList<TimingData>(committedTimingData),
                            raceTimeZone);
                },
                "stageTiming");
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

    /** A full Task Lane rejects new work rather than blocking an ingress caller. */
    private boolean offerOperation(Runnable operation) {
        return taskLane.offer(operation) == SerialLane.AdmissionResult.ACCEPTED;
    }

    private void onInitialTimingDataLoaded(List<TimingData> loadedData) {
        List<TimingData> snapshot = new ArrayList<TimingData>(loadedData);
        admitTimingFact(
                () -> rebuildDerivedTiming(snapshot),
                "initial TimingData");
    }

    private void onTimingDataCommitted(TimingData data) {
        admitTimingFact(
                () -> applyCommittedTimingData(data),
                "committed TimingData");
    }

    /**
     * Performs the bounded Data Lane -> Task Lane handoff.
     *
     * <p>The Task Lane is activated before LogBookProcessor, so normal startup
     * does not need an intermediate pending-data buffer. Rejected admission is
     * an explicit degraded-state condition rather than deferred hidden work.</p>
     */
    private void admitTimingFact(Runnable operation, String fact) {
        SerialLane.AdmissionResult admission = taskLane.offer(operation);
        if (admission == SerialLane.AdmissionResult.ACCEPTED) {
            return;
        }

        derivedTimingStale = true;
        LOG.error(
                "Task Lane rejected {} for node {}; admission={}, derived timing state is stale",
                fact,
                logBookProcessor.timingNodeId().value(),
                admission);
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

    private <R> R callOperation(Callable<R> work, String operation) {
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

        SerialLane.SubmitResult<R> submitResult =
                taskLane.submit(work);
        switch (submitResult.admission()) {
            case FULL:
                throw new OperationException(
                        OperationException.Reason.BUSY,
                        operation
                                + " could not be admitted because the TimingNode Task Lane is full");
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
                                + " could not be admitted because the TimingNode Task Lane is not running");
            case ACCEPTED:
                return await(submitResult.futureResult(), operation);
            default:
                throw new IllegalStateException(
                        "Unsupported submission result "
                                + submitResult.admission());
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
        public boolean offer(Runnable task) {
            return offerOperation(task);
        }

        @Override
        public <R> R run(Callable<R> task, String operation) {
            return callOperation(task, operation);
        }
    }
}
