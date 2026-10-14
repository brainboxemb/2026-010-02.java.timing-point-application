package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CommandAdmission;
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
 * Owns one TimingNode's committed LogBook and its Data Lane.
 *
 * <p>This component is the single execution boundary for ordered LogBook commands,
 * ORDERED queries, recovery, durable TimingData commit and publication of immutable
 * CURRENT LogBook/status views. It owns no physical thread: the supplied
 * {@link SerialLane} may share a Runtime-owned worker with other logical
 * lanes.</p>
 */
public final class LogBookProcessor implements LogBookReader {
    private static final Logger LOG =
            LoggerFactory.getLogger(LogBookProcessor.class);
    public static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 2000L;

    private final LogBookState logic;
    private final SerialLane dataLane;
    private final long operationTimeoutMillis;
    private final MonotonicClock monotonicClock;
    private final LogBookProcessorMetrics metrics;

    private final Event<Status> statusChangedEvent =
            new Event<Status>();
    private final Event<TimingData> newDataEntryEvent =
            new Event<TimingData>();
    private final Event<List<TimingData>> recoveredDataEvent =
            new Event<List<TimingData>>();

    /** Latest activation-complete immutable Status visible to CURRENT queries. */
    private volatile Status publishedStatus;

    public LogBookProcessor(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            LogBookProcessorMetrics metrics,
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

    public LogBookProcessor(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            LogBookProcessorMetrics metrics,
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

    LogBookProcessor(
            LogBookState logic,
            SerialLane dataLane,
            long operationTimeoutMillis,
            MonotonicClock monotonicClock) {
        if (logic == null) {
            throw new IllegalArgumentException("logic must not be null");
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
        this.logic = logic;
        this.dataLane = dataLane;
        this.operationTimeoutMillis = operationTimeoutMillis;
        this.monotonicClock = monotonicClock;
        this.metrics = logic.metrics();
    }

    public NodeId timingNodeId() {
        return logic.timingNodeId();
    }

    public LogBookProcessorMetrics metrics() {
        return metrics;
    }

    /**
     * Starts the Data Lane and admits persistence recovery as its first task.
     *
     * <p>The call does not wait for recovery. CURRENT status remains unavailable
     * until that first ordered task publishes the recovered status.</p>
     */
    public void activate() {
        SerialLane.State state = dataLane.state();
        if (state != SerialLane.State.NEW
                && state != SerialLane.State.STOPPED) {
            throw new IllegalStateException(
                    "LogBookProcessor cannot activate with Data Lane state="
                            + state);
        }

        publishedStatus = null;
        dataLane.start();

        SerialLane.AdmissionResult initialization =
                dataLane.offer(this::initializeOnDataLane);
        if (initialization != SerialLane.AdmissionResult.ACCEPTED) {
            dataLane.close();
            throw new IllegalStateException(
                    "TimingNode initial recovery could not be admitted: "
                            + initialization);
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
        return runSerialized(
                () -> applyCommand(command),
                command.name());
    }

    public CommandAdmission offer(
            LogBookCommand<?> command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "command must not be null");
        }

        SerialLane.AdmissionResult admission =
                dataLane.offer(
                        () -> applySubmitted(command));
        switch (admission) {
            case ACCEPTED:
                return CommandAdmission.ACCEPTED;
            case FULL:
                return CommandAdmission.FULL;
            case NOT_RUNNING:
                return CommandAdmission.NOT_RUNNING;
            default:
                throw new IllegalStateException(
                        "Unsupported admission result "
                                + admission);
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
                return runSerialized(
                        () -> query.readOrdered(logic),
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
     * Publishes one immutable recovered TimingData history after Data Lane recovery.
     *
     * <p>Task Lane-derived state can rebuild from this fact without receiving the
     * mutable LogBook itself.</p>
     */
    public EventSource<List<TimingData>> recoveredDataEvent() {
        return recoveredDataEvent;
    }

    /** Admits one fire-and-forget operation to the Data Lane. */
    public boolean offerTask(
            Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }
        return dataLane.offer(task)
                == SerialLane.AdmissionResult.ACCEPTED;
    }

    /** Runs one synchronous helper operation on the Data Lane. */
    public void runTask(
            Runnable task,
            String operation) {
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }
        runSerialized(
                () -> {
                    task.run();
                    return null;
                },
                operation);
    }

    public <R> R runTask(
            Callable<R> task,
            String operation) {
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }
        return runSerialized(
                task,
                operation);
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
        return logic.timingDataHead();
    }
    @Override
    public List<TimingData> timingDataRange(long fromSequence, int limit) {
        requireDataLane("timingDataRange");
        List<TimingData> records = new ArrayList<TimingData>(limit);
        logic.logBook().visitRange(fromSequence, limit, records::add);
        return records;
    }

    private void requireDataLane(String operation) {
        if (!dataLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    operation + " requires the TimingNode Data Lane");
        }
    }

    long currentTimingDataHead() {
        return logic.timingDataHead();
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
                    "status CURRENT result is unavailable because TimingNode recovery is not complete");
        }
        return status;
    }

    private void initializeOnDataLane() {
        LOG.info(
                "TimingNode {} TimingData recovery started",
                timingNodeId().value());

        final TimingDataPersistence.LoadResult recovery;
        try {
            recovery =
                    logic.recoverTimingData();
        } catch (TimingDataPersistence.PersistenceException
                | RuntimeException ex) {
            logic.markTimingDataRecoveryFailed(ex);
            LOG.error(
                    "TimingData recovery failed for {}; TimingNode remains available in ERROR state",
                    timingNodeId().value(),
                    ex);
            publishRecoveredStatus();
            return;
        }

        LOG.info(
                "TimingNode {} TimingData recovery completed: records={}, tailRepaired={}",
                timingNodeId().value(),
                recovery.records().size(),
                recovery.repairedIncompleteTail());
        if (recovery.repairedIncompleteTail()) {
            LOG.warn(
                    "TimingNode {} TimingData recovery repaired an incomplete final record tail",
                    timingNodeId().value());
        }

        publishRecoveredData(recovery.records());
        publishRecoveredStatus();
    }

    private void publishRecoveredStatus() {
        Status initialStatus =
                logic.status();
        publishCurrentStatus(
                initialStatus);
        publishInitialStatus(
                initialStatus);
    }

    private <R> void applySubmitted(
            LogBookCommand<R> command) {
        try {
            applyCommand(command);
        } catch (Exception ex) {
            LOG.warn(
                    "Submitted TimingNode command {} failed after admission",
                    command.name(),
                    ex);
        }
    }

    private <R> R applyCommand(
            LogBookCommand<R> command)
            throws Exception {
        Status before =
                logic.status();
        int committedBefore =
                logic.committedTimingDataCount();

        R result =
                command.apply(logic);

        Status after =
                logic.status();
        publishCurrentStatus(
                after);

        int committedAfter =
                logic.committedTimingDataCount();
        if (committedAfter > committedBefore) {
            publishNewData(
                    logic.latestCommittedTimingData());
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

    private void publishRecoveredData(
            List<TimingData> recovered) {
        List<TimingData> snapshot =
                Collections.unmodifiableList(
                        new ArrayList<TimingData>(recovered));
        Event.DeliveryReport delivery =
                recoveredDataEvent.emit(snapshot);
        if (!delivery.successful()) {
            LOG.warn(
                    "TimingData recovered but "
                            + delivery.failureCount()
                            + " recoveredDataEvent listener(s) failed for "
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

    private <R> R runSerialized(
            Callable<R> work,
            String operation) {
        if (dataLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    "Reentrant ORDERED TimingNode operation "
                            + operation
                            + " cannot wait for the same Data Lane");
        }

        SerialLane.SubmitResult<R> submitResult =
                dataLane.submit(work);
        switch (submitResult.admission()) {
            case FULL:
                throw new OperationException(
                        OperationException.Reason.BUSY,
                        operation
                                + " could not be admitted because the TimingNode Data Lane is full");
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
                                + " could not be admitted because the TimingNode Data Lane is not running");
            case ACCEPTED:
                return await(
                        submitResult.futureResult(),
                        operation);
            default:
                throw new IllegalStateException(
                        "Unsupported submission result "
                                + submitResult.admission());
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
        if (left.timingDataTailRecovered()
                != right.timingDataTailRecovered()) {
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
