package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.ArrayList;
import java.util.Collections;
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
 * Data Lane component for one TimingNode's committed timing history.
 *
 * <p>LogBook is the single execution boundary for ordered commands,
 * ORDERED queries, persisted-data startup loading, durable TimingData commit and publication of immutable
 * CURRENT status/history views. It owns no physical thread: the supplied
 * {@link SerialLane} may share a Runtime-owned worker with other logical
 * lanes.</p>
 */
public final class LogBook
        implements LogBookAccessor {
    private static final Logger LOG =
            LoggerFactory.getLogger(LogBook.class);
    public static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 2000L;

    private final LogBookState logBookState;
    private final SerialLane serialLane;
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

    /**
     * Creates one LogBook bound to the supplied Data Lane.
     *
     * <p>The timeout applies only when a caller explicitly waits for a
     * result-bearing Data Lane operation. Fire-and-forget queueing uses the
     * standard {@link SerialLane#execute(Runnable)} rejection contract.</p>
     */
    public LogBook(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            LogBookMetrics metrics,
            SerialLane serialLane,
            long operationTimeoutMillis) {
        checkArgument(timingNodeId != null, "timingNodeId must not be null");
        checkArgument(timingDataPersistence != null, "timingDataPersistence must not be null");
        checkArgument(timingDataFactory != null, "timingDataFactory must not be null");
        checkArgument(timeSource != null, "timeSource must not be null");
        checkArgument(monotonicClock != null, "monotonicClock must not be null");
        checkArgument(metrics != null, "metrics must not be null");
        checkArgument(serialLane != null, "serialLane must not be null");
        checkArgument(operationTimeoutMillis > 0L, "operationTimeoutMillis must be positive");

        this.logBookState = new LogBookState(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                monotonicClock,
                metrics);
        this.serialLane = serialLane;
        this.operationTimeoutMillis = operationTimeoutMillis;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
    }

    /** Returns immutable node identity without entering the Data Lane. */
    public NodeId timingNodeId() {
        return logBookState.timingNodeId();
    }

    /** Returns engineering metrics for this LogBook and its commit path. */
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
        serialLane.start();
        publishedStatus = null;

        SafeExecute.onRejected(
                () -> serialLane.execute(this::loadPersistedData),
                serialLane::close,
                "Persisted TimingData load could not be scheduled");
    }

    /**
     * Stops new Data Lane work and makes CURRENT status unavailable.
     *
     * <p>Lane shutdown/drain semantics are owned by {@link SerialLane}; LogBook
     * does not implement a second shutdown queue.</p>
     */
    public void deactivate() {
        serialLane.close();
        publishedStatus = null;
    }

    /**
     * Executes one command in Data Lane order and waits for its domain result.
     */
    public <R> R execute(
            LogBookCommand<R> command) {
        checkArgument(command != null, "command must not be null");
        return executeOnDataLaneAndWait(
                () -> applyCommand(command),
                command.name());
    }

    /**
     * Queues one fire-and-forget command on the Data Lane.
     *
     * <p>The method returns after the command is queued. It does not wait
     * for command execution. If the bounded lane cannot queue the command,
     * {@link RejectedExecutionException} propagates to the caller that owns the
     * appropriate handling policy.</p>
     */
    public void queue(LogBookCommand<?> command) {
        checkArgument(command != null, "command must not be null");
        serialLane.execute(() -> executeQueuedCommand(command));
    }

    /**
     * Executes an ORDERED query and waits until earlier queued Data Lane work
     * has been processed.
     */
    public <R> R query(
            LogBookQuery<R> query) {
        return query(
                query,
                LogBookQuery.ReadConsistency.ORDERED);
    }

    /**
     * Reads either the directly published CURRENT view or an ORDERED Data Lane
     * view, according to the requested consistency.
     */
    public <R> R query(
            LogBookQuery<R> query,
            LogBookQuery.ReadConsistency consistency) {
        checkArgument(query != null, "query must not be null");
        checkArgument(consistency != null, "consistency must not be null");

        switch (consistency) {
            case CURRENT:
                return query.readCurrent(this);
            case ORDERED:
                /*
                 * When already on the Data Lane, earlier queued work has
                 * necessarily been processed before this turn. Reading the
                 * ordered view directly avoids self-queueing/waiting while
                 * preserving the same ordering guarantee.
                 */
                if (serialLane.isExecutingOnCurrentThread()) {
                    return query.readOrdered(logBookState);
                }
                return executeOnDataLaneAndWait(
                        () -> query.readOrdered(logBookState),
                        query.name());
            default:
                throw new IllegalStateException(
                        "Unsupported read consistency "
                                + consistency);
        }
    }

    /** Publishes status changes synchronously on the Data Lane after commit. */
    public EventSource<Status> statusChangedEvent() {
        return statusChangedEvent;
    }

    /** Publishes each newly committed TimingData record on the Data Lane. */
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
     * Executes one internal fire-and-forget operation on the Data Lane.
     *
     * <p>This is the raw execution seam used by node-local coordination code.
     * Rejection uses the same standard exception contract as SerialLane.</p>
     */
    public void executeOnDataLane(Runnable operation) {
        checkArgument(operation != null, "operation must not be null");
        serialLane.execute(operation);
    }

    /** Returns the committed TimingData head while already inside LogBook query handling. */
    long currentTimingDataHead() {
        return logBookState.timingDataHead();
    }

    /** Returns the latest safely published CURRENT status or explains why none is available. */
    Status publishedStatus() {
        SerialLane.State state =
                serialLane.state();
        if (state == SerialLane.State.FAILED) {
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    "status CURRENT result is unavailable because the Data Lane failed",
                    serialLane.failure());
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

    /** Loads persisted TimingData and publishes the reconstructed LogBook state. */
    private void loadPersistedData() {
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

    /** Publishes the first immutable status after startup loading finishes or fails. */
    private void publishStatusAfterInitialLoad() {
        Status initialStatus =
                logBookState.status();
        publishCurrentStatus(
                initialStatus);
        publishInitialStatus(
                initialStatus);
    }

    /** Executes one previously queued fire-and-forget command and contains its later failure. */
    private <R> void executeQueuedCommand(
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

    /** Applies one command to mutable LogBook state and publishes its completed effects. */
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

    /** Emits the first status fact after persisted data has been reconstructed. */
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

    /** Emits a status event only when the effective status changed. */
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

    /** Replaces the volatile immutable status snapshot used by CURRENT queries. */
    private void publishCurrentStatus(
            Status status) {
        checkArgument(status != null, "status must not be null");
        publishedStatus = status;
    }

    /** Emits an immutable startup-history snapshot to node-local derived-state consumers. */
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

    /** Publishes one successfully committed TimingData record and records delivery metrics. */
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
        if (serialLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    "Reentrant ORDERED TimingNode operation "
                            + operation
                            + " cannot wait for the same Data Lane");
        }

        final Future<R> future;
        try {
            future = serialLane.submit(work);
        } catch (RejectedExecutionException ex) {
            throw rejectedOperation(operation, ex);
        }
        return await(future, operation);
    }

    /** Converts Future completion into the stable LogBook operation exception contract. */
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

    /**
     * Maps standard lane rejection to the LogBook operation categories used by result-bearing calls.
     *
     * <p>The lane state distinguishes a stopped/failed owner from ordinary bounded-queue pressure;
     * SerialLane itself keeps the standard RejectedExecutionException API.</p>
     */
    private OperationException rejectedOperation(
            String operation,
            RejectedExecutionException rejection) {
        if (serialLane.state() == SerialLane.State.FAILED) {
            return new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " could not run because the TimingNode Data Lane failed",
                    serialLane.failure() == null ? rejection : serialLane.failure());
        }
        if (serialLane.state() != SerialLane.State.RUNNING) {
            return new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    operation + " could not be queued because the TimingNode Data Lane is not running",
                    rejection);
        }
        return new OperationException(
                OperationException.Reason.BUSY,
                operation + " could not be queued because the TimingNode Data Lane is full",
                rejection);
    }

    /** Compares immutable status values without depending on object identity. */
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
