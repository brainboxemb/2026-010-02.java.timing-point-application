package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CommandSubmissionResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.ArrayList;
import java.util.Collections;
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
 * Data Lane component for one TimingNode's committed timing history.
 *
 * <p>LogBook is the single execution boundary for ordered commands,
 * ORDERED queries, persisted-data startup loading, durable TimingData commit and publication of immutable
 * CURRENT status/history views. It owns no physical thread: the supplied
 * {@link SerialLane} may share a Runtime-owned worker with other logical
 * lanes.</p>
 */
public final class LogBook
        implements LogBookReader, LogBookAccessor {
    private static final Logger LOG =
            LoggerFactory.getLogger(LogBook.class);
    public static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 2000L;

    private final LogBookState logBookState;
    private final SerialLane dataLane;
    private final long operationTimeoutMillis;
    private final MonotonicClock monotonicClock;
    private final LogBookMetrics metrics;

    private final Event<Status> statusChangedEvent =
            new Event<Status>();
    private final Event<TimingData> newDataEntryEvent =
            new Event<TimingData>();
    private final Event<List<TimingData>> initialDataLoadedEvent =
            new Event<List<TimingData>>();

    /** Latest activation-complete immutable Status visible to CURRENT queries. */
    private volatile Status publishedStatus;

    public LogBook(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            LogBookMetrics metrics,
            SerialLane dataLane) {
        this(
                new LogBookState(
                        timingNodeId,
                        timingDataPersistence,
                        timingDataFactory,
                        timeSource,
                        monotonicClock,
                        metrics),
                dataLane,
                DEFAULT_OPERATION_TIMEOUT_MILLIS,
                monotonicClock);
    }

    public LogBook(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            LogBookMetrics metrics,
            SerialLane dataLane,
            long operationTimeoutMillis) {
        this(
                new LogBookState(
                        timingNodeId,
                        timingDataPersistence,
                        timingDataFactory,
                        timeSource,
                        monotonicClock,
                        metrics),
                dataLane,
                operationTimeoutMillis,
                monotonicClock);
    }

    LogBook(
            LogBookState logBookState,
            SerialLane dataLane,
            long operationTimeoutMillis,
            MonotonicClock monotonicClock) {
        if (logBookState == null) {
            throw new IllegalArgumentException("logBookState must not be null");
        }
        if (dataLane == null) {
            throw new IllegalArgumentException("dataLane must not be null");
        }
        if (operationTimeoutMillis < 1L) {
            throw new IllegalArgumentException(
                    "operationTimeoutMillis must be positive");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException(
                    "monotonicClock must not be null");
        }
        this.logBookState = logBookState;
        this.dataLane = dataLane;
        this.operationTimeoutMillis = operationTimeoutMillis;
        this.monotonicClock = monotonicClock;
        this.metrics = logBookState.metrics();
    }

    public NodeId timingNodeId() {
        return logBookState.timingNodeId();
    }

    public LogBookMetrics metrics() {
        return metrics;
    }

    /**
     * Starts the Data Lane and queues persisted TimingData loading as its first task.
     *
     * <p>The call does not wait for file I/O. CURRENT status remains unavailable
     * until that first ordered task has rebuilt the LogBook and published status.</p>
     */
    public void activate() {
        SerialLane.State state = dataLane.state();
        if (state != SerialLane.State.NEW
                && state != SerialLane.State.STOPPED) {
            throw new IllegalStateException(
                    "LogBook cannot activate with Data Lane state="
                            + state);
        }

        publishedStatus = null;
        dataLane.start();

        SerialLane.AdmissionResult loadSubmissionResult =
                dataLane.offer(this::loadPersistedDataOnDataLane);
        if (loadSubmissionResult != SerialLane.AdmissionResult.ACCEPTED) {
            dataLane.close();
            throw new IllegalStateException(
                    "Persisted TimingData load could not be queued on the Data Lane: "
                            + loadSubmissionResult);
        }
    }

    public void deactivate() {
        dataLane.close();
        publishedStatus = null;
    }

    public <R> R invoke(
            LogBookCommand<R> command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "command must not be null");
        }
        return executeOnDataLaneAndWait(
                () -> applyCommand(command),
                command.name());
    }

    /**
     * Submits one fire-and-forget command to the Data Lane.
     *
     * <p>This method never waits for command execution. {@code ACCEPTED} means
     * only that the command was queued; execution and any later failure happen
     * asynchronously on the Data Lane.</p>
     */
    public CommandSubmissionResult submit(
            LogBookCommand<?> command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "command must not be null");
        }

        SerialLane.AdmissionResult laneSubmissionResult =
                dataLane.offer(
                        () -> executeSubmittedCommand(command));
        switch (laneSubmissionResult) {
            case ACCEPTED:
                return CommandSubmissionResult.ACCEPTED;
            case FULL:
                return CommandSubmissionResult.QUEUE_FULL;
            case NOT_RUNNING:
                return CommandSubmissionResult.NOT_RUNNING;
            default:
                throw new IllegalStateException(
                        "Unsupported Data Lane submission result "
                                + laneSubmissionResult);
        }
    }

    public <R> R query(
            LogBookQuery<R> query) {
        return query(
                query,
                LogBookQuery.ReadConsistency.ORDERED);
    }

    public <R> R query(
            LogBookQuery<R> query,
            LogBookQuery.ReadConsistency consistency) {
        if (query == null) {
            throw new IllegalArgumentException(
                    "query must not be null");
        }
        if (consistency == null) {
            throw new IllegalArgumentException(
                    "consistency must not be null");
        }

        switch (consistency) {
            case CURRENT:
                return query.readCurrent(this);
            case ORDERED:
                return executeOnDataLaneAndWait(
                        () -> query.readOrdered(logBookState),
                        query.name());
            default:
                throw new IllegalStateException(
                        "Unsupported read consistency "
                                + consistency);
        }
    }

    public EventSource<Status> statusChangedEvent() {
        return statusChangedEvent;
    }

    public EventSource<TimingData> newDataEntryEvent() {
        return newDataEntryEvent;
    }

    /**
     * Publishes the immutable TimingData history loaded during node startup.
     *
     * <p>Task Lane-derived state can initialize from this fact without receiving
     * the mutable LogBook itself.</p>
     */
    public EventSource<List<TimingData>> initialDataLoadedEvent() {
        return initialDataLoadedEvent;
    }

    /**
     * Tries to queue one fire-and-forget operation on the Data Lane.
     *
     * <p>Returns {@code true} only when the operation was queued. The caller
     * does not wait for execution or receive the operation result.</p>
     */
    public boolean trySubmitOperation(
            Runnable operation) {
        if (operation == null) {
            throw new IllegalArgumentException(
                    "operation must not be null");
        }
        return dataLane.offer(operation)
                == SerialLane.AdmissionResult.ACCEPTED;
    }

    /**
     * Narrow committed-history operations exposed independently of any consumer.
     *
     * <p>The scalar head is safely published by LogBook. Bounded history access
     * remains Data Lane-only. Upstream/post-processing may depend on this data
     * contract; this component never depends on those consumers.</p>
     */
    @Override
    public long timingDataHead() {
        return logBookState.timingDataHead();
    }
    @Override
    public List<TimingData> timingDataRange(long fromSequence, int limit) {
        requireDataLane("timingDataRange");
        List<TimingData> records = new ArrayList<TimingData>(limit);
        logBookState.logBookData().visitRange(fromSequence, limit, records::add);
        return records;
    }

    private void requireDataLane(String operation) {
        if (!dataLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    operation + " requires the TimingNode Data Lane");
        }
    }

    long currentTimingDataHead() {
        return logBookState.timingDataHead();
    }

    Status publishedStatus() {
        SerialLane.State state =
                dataLane.state();
        if (state == SerialLane.State.FAILED) {
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    "status CURRENT result is unavailable because the Data Lane failed",
                    dataLane.failure());
        }
        if (state != SerialLane.State.RUNNING) {
            throw new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    "status CURRENT result is unavailable because the LogBook component is not active");
        }

        Status status = publishedStatus;
        if (status == null) {
            throw new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    "status CURRENT result is unavailable because persisted TimingData is not loaded yet");
        }
        return status;
    }

    private void loadPersistedDataOnDataLane() {
        LOG.info(
                "TimingNode {} persisted TimingData load started",
                timingNodeId().value());

        final TimingDataPersistence.LoadResult loadResult;
        try {
            loadResult =
                    logBookState.loadPersistedTimingData();
        } catch (TimingDataPersistence.PersistenceException
                | RuntimeException ex) {
            logBookState.markTimingDataLoadFailed(ex);
            LOG.error(
                    "Persisted TimingData load failed for {}; TimingNode remains available in ERROR state",
                    timingNodeId().value(),
                    ex);
            publishStatusAfterInitialLoad();
            return;
        }

        LOG.info(
                "TimingNode {} persisted TimingData load completed: records={}, tailRepaired={}",
                timingNodeId().value(),
                loadResult.records().size(),
                loadResult.repairedIncompleteTail());
        if (loadResult.repairedIncompleteTail()) {
            LOG.warn(
                    "TimingNode {} repaired an incomplete final TimingData record tail during load",
                    timingNodeId().value());
        }

        publishInitialLoadedData(loadResult.records());
        publishStatusAfterInitialLoad();
    }

    private void publishStatusAfterInitialLoad() {
        Status initialStatus =
                logBookState.status();
        publishCurrentStatus(
                initialStatus);
        publishInitialStatus(
                initialStatus);
    }

    private <R> void executeSubmittedCommand(
            LogBookCommand<R> command) {
        try {
            applyCommand(command);
        } catch (Exception ex) {
            LOG.warn(
                    "Queued TimingNode command {} failed during Data Lane execution",
                    command.name(),
                    ex);
        }
    }

    private <R> R applyCommand(
            LogBookCommand<R> command)
            throws Exception {
        Status before =
                logBookState.status();
        int committedBefore =
                logBookState.committedTimingDataCount();

        R result =
                command.apply(logBookState);

        Status after =
                logBookState.status();
        publishCurrentStatus(
                after);

        int committedAfter =
                logBookState.committedTimingDataCount();
        if (committedAfter > committedBefore) {
            publishNewData(
                    logBookState.latestCommittedTimingData());
        }

        publishStatusChanged(
                before,
                after);
        return result;
    }

    private void publishInitialStatus(
            Status status) {
        LOG.info(
                "TimingNode {} initial status available: state {}, location {}, problems {}",
                timingNodeId().value(),
                status.state(),
                status.hasLocation()
                        ? status.locationId()
                        : "-",
                status.problems().size());

        Event.DeliveryReport delivery =
                statusChangedEvent.emit(status);
        if (!delivery.successful()) {
            LOG.warn(
                    "TimingNode initial status published but "
                            + delivery.failureCount()
                            + " status listener(s) failed for "
                            + timingNodeId().value(),
                    delivery.failures().get(0));
        }
    }

    private void publishStatusChanged(
            Status before,
            Status after) {
        if (sameStatus(
                before,
                after)) {
            return;
        }

        LOG.info(
                "TimingNode {} status changed: state {} -> {}, location {} -> {}, problems {} -> {}",
                timingNodeId().value(),
                before.state(),
                after.state(),
                before.hasLocation()
                        ? before.locationId()
                        : "-",
                after.hasLocation()
                        ? after.locationId()
                        : "-",
                before.problems().size(),
                after.problems().size());

        Event.DeliveryReport delivery =
                statusChangedEvent.emit(after);
        if (!delivery.successful()) {
            LOG.warn(
                    "TimingNode status changed but "
                            + delivery.failureCount()
                            + " status listener(s) failed for "
                            + timingNodeId().value(),
                    delivery.failures().get(0));
        }
    }

    private void publishCurrentStatus(
            Status status) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "status must not be null");
        }
        publishedStatus = status;
    }

    private void publishInitialLoadedData(
            List<TimingData> loadedData) {
        List<TimingData> snapshot =
                Collections.unmodifiableList(
                        new ArrayList<TimingData>(loadedData));
        Event.DeliveryReport delivery =
                initialDataLoadedEvent.emit(snapshot);
        if (!delivery.successful()) {
            LOG.warn(
                    "Persisted TimingData loaded but "
                            + delivery.failureCount()
                            + " initialDataLoadedEvent listener(s) failed for "
                            + timingNodeId().value(),
                    delivery.failures().get(0));
        }
    }

    private void publishNewData(
            TimingData data) {
        long eventStartedNanos =
                monotonicClock.nowNanos();
        Event.DeliveryReport delivery =
                newDataEntryEvent.emit(data);
        metrics.recordEventDelivery(
                monotonicClock.nowNanos()
                        - eventStartedNanos,
                delivery.failureCount());
        if (!delivery.successful()) {
            LOG.warn(
                    "TimingData committed but "
                            + delivery.failureCount()
                            + " newDataEntryEvent listener(s) failed for "
                            + timingNodeId().value()
                            + ":"
                            + data.sequenceNumber(),
                    delivery.failures().get(0));
        }
    }

    /**
     * Queues one result-bearing operation on the Data Lane and waits for its
     * ordered execution result.
     */
    private <R> R executeOnDataLaneAndWait(
            Callable<R> work,
            String operation) {
        if (dataLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    "Reentrant ORDERED TimingNode operation "
                            + operation
                            + " cannot wait for the same Data Lane");
        }

        SerialLane.SubmitResult<R> submittedWork =
                dataLane.submit(work);
        SerialLane.AdmissionResult submissionResult =
                submittedWork.admission();
        switch (submissionResult) {
            case FULL:
                throw new OperationException(
                        OperationException.Reason.BUSY,
                        operation
                                + " could not be queued because the TimingNode Data Lane is full");
            case NOT_RUNNING:
                if (dataLane.state()
                        == SerialLane.State.FAILED) {
                    throw new OperationException(
                            OperationException.Reason.FAILED,
                            operation
                                    + " could not run because the TimingNode Data Lane failed",
                            dataLane.failure());
                }
                throw new OperationException(
                        OperationException.Reason.UNAVAILABLE,
                        operation
                                + " could not be queued because the TimingNode Data Lane is not running");
            case ACCEPTED:
                return await(
                        submittedWork.futureResult(),
                        operation);
            default:
                throw new IllegalStateException(
                        "Unsupported submission result "
                                + submissionResult);
        }
    }

    private <R> R await(
            Future<R> future,
            String operation) {
        try {
            return future.get(
                    operationTimeoutMillis,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new OperationException(
                    OperationException.Reason.TIMEOUT,
                    operation
                            + " timed out; final TimingNode outcome is unknown",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OperationException(
                    OperationException.Reason.INTERRUPTED,
                    operation
                            + " was interrupted while waiting for the TimingNode result",
                    ex);
        } catch (CancellationException ex) {
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    operation
                            + " was cancelled because the TimingNode Data Lane failed",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause =
                    ex.getCause();
            String detail =
                    cause == null
                            ? null
                            : cause.getMessage();
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    operation
                            + " failed while executing on the TimingNode Data Lane"
                            + (detail == null
                                    || detail.trim().isEmpty()
                                            ? ""
                                            : ": "
                                                    + detail.trim()),
                    cause);
        }
    }

    private static boolean sameStatus(
            Status left,
            Status right) {
        if (!left.timingNodeId()
                .equals(
                        right.timingNodeId())) {
            return false;
        }
        if (left.state()
                != right.state()) {
            return false;
        }
        if (left.timingDataTailRepaired()
                != right.timingDataTailRepaired()) {
            return false;
        }
        if (!left.problems()
                .equals(
                        right.problems())) {
            return false;
        }
        if (!left.hasLocation()) {
            return !right.hasLocation();
        }
        return right.hasLocation()
                && left.locationId()
                        .equals(
                                right.locationId());
    }
}
