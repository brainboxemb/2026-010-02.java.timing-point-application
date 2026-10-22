package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventWithMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ExecutionMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Data Lane component for one TimingNode's committed TimingData.
 *
 * <p>LogBook owns persisted TimingData loading plus the Data Lane boundary for ordered
 * commands, ORDERED queries and durable TimingData commit. Loading runs when
 * TimingNode.MainTask calls {@link #loadPersistedData()}. The LogBook Data Lane may share
 * a Runtime-owned executor with TimingNode's Task Lane, but its queue and lifecycle belong
 * to this LogBook.</p>
 */
public final class LogBook extends LifecycleComponent implements LogBookAccessor, LogBookTestControl {
    private static final Logger LOG = LoggerFactory.getLogger(LogBook.class);
    public static final long DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS = 5000L;
    public static final long DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS = 2000L;

    private final LogBookState logBookState;
    private final LogBookOperations logBookOperations;
    private final SerialLane<LogBookOperationItem<?>> dataLane;
    private final LogBookMetrics metrics;
    private final long commitWaitTimeoutMillis;
    private final long orderedQueryWaitTimeoutMillis;
    private final AtomicReference<WriteFailure> nextWriteFailure =
            new AtomicReference<WriteFailure>();

    private final EventWithMetrics<TimingRecord> newDataEntryEvent;
    private final Event<LogBookStatus> statusEvent = new Event<LogBookStatus>();
    private final Event<LogBookFailure> commandFailedEvent = new Event<LogBookFailure>();
    private volatile LogBookStatus status = LogBookStatus.INIT;

    /** LogBook availability status source. */
    public final EventSource<LogBookStatus> STATUS = statusEvent;

    /** Post-commit scalar timing-record source. */
    public final EventSource<TimingRecord> NEW_DATA_ENTRY;

    /** Failed LogBook command source. */
    public final EventSource<LogBookFailure> COMMAND_FAILED = commandFailedEvent;

    /**
     * Creates one LogBook bound to the supplied Data Lane.
     *
     * <p>The commit wait timeout applies to result-bearing TimingData commands.
     * The ordered-query wait timeout applies only to ORDERED reads. CURRENT reads
     * do not wait on the Data Lane. Fire-and-forget queue rejection is contained
     * and reported by LogBook because there is no caller result.</p>
     */
    public LogBook(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            LogBookMetrics metrics,
            Executor dataLaneExecutor,
            int dataLaneCapacity,
            int dataLaneMaxItemsPerRun,
            long commitWaitTimeoutMillis,
            long orderedQueryWaitTimeoutMillis) {

        failIfInvalidArgument(timingNodeId != null, "timingNodeId must not be null");
        failIfInvalidArgument(timingDataPersistence != null, "timingDataPersistence must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");
        failIfInvalidArgument(dataLaneExecutor != null, "dataLaneExecutor must not be null");
        failIfInvalidArgument(dataLaneCapacity > 0, "dataLaneCapacity must be positive");
        failIfInvalidArgument(dataLaneMaxItemsPerRun > 0, "dataLaneMaxItemsPerRun must be positive");
        failIfInvalidArgument(commitWaitTimeoutMillis > 0L, "commitWaitTimeoutMillis must be positive");
        failIfInvalidArgument(orderedQueryWaitTimeoutMillis > 0L, "orderedQueryWaitTimeoutMillis must be positive");

        this.logBookState = new LogBookState(timingNodeId);
        this.logBookOperations = new LogBookOperations(
                logBookState,
                timingDataPersistence,
                timeSource,
                monotonicClock,
                metrics);
        this.dataLane =
                new SerialLane<LogBookOperationItem<?>>(
                        dataLaneCapacity,
                        dataLaneMaxItemsPerRun,
                        "LogBookData",
                        dataLaneExecutor,
                        this::processDataLaneItem);
        this.metrics = metrics;
        this.commitWaitTimeoutMillis = commitWaitTimeoutMillis;
        this.orderedQueryWaitTimeoutMillis = orderedQueryWaitTimeoutMillis;
        this.newDataEntryEvent =
                new EventWithMetrics<TimingRecord>(
                        monotonicClock,
                        metrics.timingDataEventMetrics());
        this.NEW_DATA_ENTRY = newDataEntryEvent;
    }

    /** Returns immutable node identity without entering the Data Lane. */
    public NodeId timingNodeId() {

        return logBookState.timingNodeId();
    }

    /** Returns engineering metrics for this LogBook and its commit path. */
    public LogBookMetrics metrics() {

        return metrics;
    }

    /** Returns engineering metrics for the owned LogBook Data Lane. */
    public ExecutionMetrics dataLaneMetrics() {

        return dataLane.metrics();
    }

    /** Arms one engineering/test failure for the next LogBook command write. */
    @Override
    public void armNextWriteFailure(WriteFailure failure) {

        failIfInvalidArgument(failure != null, "failure must not be null");
        if (!nextWriteFailure.compareAndSet(null, failure)) {
            throw new IllegalStateException(
                    "A LogBook write test failure is already armed");
        }
    }

    /**
     * Marks the LogBook lifecycle-active.
     *
     * <p>TimingNode.MainTask starts persisted TimingData loading after child
     * activation. Data Lane work may queue while that load runs on the shared worker.</p>
     */
    @Override
    protected void onActivate() {

        /*
         * Normal operations may already queue while TimingNode.MainTask loads
         * persisted state. Data Lane and Task Lane share the same node worker,
         * so those operations cannot execute until the startup task yields.
         */
        dataLane.start();
    }

    /** Stops new Data Lane work and returns status to INIT. */
    @Override
    protected void onDeactivate() {

        dataLane.close();
        status = LogBookStatus.INIT;
    }

    /**
     * Processes one command in LogBook order and returns its domain result.
     *
     * @throws IllegalStateException when ordered execution cannot return a result
     */
    public <R> R process(LogBookCommand<R> command) {

        failIfInvalidArgument(command != null, "command must not be null");
        failIfNotReentrant(command.name());

        CommandOperationItem<R> item =
                new CommandOperationItem<R>(command, true);
        try {
            offerOrReject(item);
        } catch (RejectedExecutionException ex) {
            throw rejectedOperation(command.name(), "command", ex);
        }

        return await(
                item.result(),
                command.name(),
                "command",
                commitWaitTimeoutMillis,
                true);
    }

    /**
     * Executes one fire-and-forget command asynchronously.
     *
     * <p>The method returns after queueing. Queue rejection is a LogBook
     * diagnostic because this fire-and-forget operation has no caller result.</p>
     */
    public void execute(LogBookCommand<?> command) {

        failIfInvalidArgument(command != null, "command must not be null");
        SafeExecute.runOrHandleRejection(
                () -> executeCommand(command),
                ex -> LOG.error(
                        "LogBook command {} for node {} could not be queued on the Data Lane",
                        command.name(),
                        timingNodeId().value(),
                        ex));
    }

    /**
     * Queues one ORDERED query and returns its completion without waiting.
     *
     * <p>If already on the Data Lane, the ordered requirement is already
     * satisfied and the result is returned as an already-completed future.</p>
     */
    @Override
    public <R> CompletableFuture<R> submit(LogBookQuery<R> query) {

        failIfInvalidArgument(query != null, "query must not be null");

        if (dataLane.isExecutingOnCurrentThread()) {
            try {
                return CompletableFuture.completedFuture(
                        query.readOrdered(logBookState));
            } catch (RuntimeException ex) {
                CompletableFuture<R> failed = new CompletableFuture<R>();
                failed.completeExceptionally(ex);
                return failed;
            }
        }

        QueryOperationItem<R> item = new QueryOperationItem<R>(query);
        try {
            offerOrReject(item);
        } catch (RejectedExecutionException ex) {
            throw rejectedOperation(query.name(), "query", ex);
        }
        return item.result();
    }

    /**
     * Processes an ORDERED query and waits until earlier queued Data Lane work
     * has been processed.
     *
     * @throws IllegalStateException when ordered execution cannot return a result
     */
    public <R> R process(LogBookQuery<R> query) {

        return process(query, LogBookQuery.ReadConsistency.ORDERED);
    }

    /**
     * Processes either the directly published CURRENT view or an ORDERED Data
     * Lane view, according to the requested consistency.
     *
     * @throws IllegalStateException when ordered execution cannot return a result
     */
    public <R> R process(LogBookQuery<R> query, LogBookQuery.ReadConsistency consistency) {

        failIfInvalidArgument(query != null, "query must not be null");
        failIfInvalidArgument(consistency != null, "consistency must not be null");

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
                if (dataLane.isExecutingOnCurrentThread()) {
                    return query.readOrdered(logBookState);
                }
                failIfNotReentrant(query.name());
                return await(
                        submit(query),
                        query.name(),
                        "query",
                        orderedQueryWaitTimeoutMillis,
                        false);
            default:
                throw new IllegalStateException("Unsupported read consistency " + consistency);
        }
    }

    /** Returns the committed TimingData head while already inside LogBook query handling. */
    long currentTimingDataHead() {

        return logBookState.timingDataHead();
    }

    /** Returns the current LogBook availability status. */
    @Override
    public LogBookStatus status() {

        return status;
    }

    /**
     * Loads persisted TimingData as the TimingNode MainTask startup step.
     *
     * <p>The call is intentionally synchronous. TimingNode invokes it from its
     * asynchronous MainTask on the shared node worker. Data Lane operations may
     * queue meanwhile, but cannot execute on that same worker until this startup
     * step returns.</p>
     */
    public void loadPersistedData() {
        failIfInactive();
        LOG.info(
                "TimingNode {} persisted TimingData load started",
                timingNodeId().value());

        LogBookStatus loadedStatus;
        try {
            TimingDataPersistence.LoadResult loadResult =
                    logBookOperations.loadPersistedTimingData();

            // The blocking load may outlive TimingNode deactivation. Do not
            // restore or publish startup state for an inactive LogBook.
            failIfInactive();
            logBookOperations.restorePersistedTimingData(loadResult);

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
            loadedStatus = LogBookStatus.LOADED;

        } catch (TimingDataPersistence.PersistenceException | RuntimeException ex) {
            // An inactive LogBook is a cancelled startup flow, not a load failure.
            failIfInactive();
            logBookOperations.markTimingDataLoadFailed(ex);
            LOG.error(
                    "Persisted TimingData load failed for {}",
                    timingNodeId().value(),
                    ex);
            loadedStatus = LogBookStatus.ERROR;
        }

        failIfInactive();
        publishStatus(loadedStatus);
    }

    /** Publishes one LogBook status change. */
    private void publishStatus(LogBookStatus newStatus) {

        status = newStatus;
        LOG.info("TimingNode {} LogBook status {}", timingNodeId().value(), newStatus);
        statusEvent.emit(newStatus);
    }

    /** One queued state-changing LogBook command. */
    private static final class CommandOperationItem<R> extends LogBookOperationItem<R> {
        private final LogBookCommand<R> command;

        private CommandOperationItem(LogBookCommand<R> command, boolean callerWaits) {
            super(command.name(), callerWaits, callerWaits);
            this.command = command;
        }

        private LogBookCommand<R> command() {
            return command;
        }
    }

    /** One queued ORDERED LogBook query. */
    private static final class QueryOperationItem<R> extends LogBookOperationItem<R> {
        private final LogBookQuery<R> query;

        private QueryOperationItem(LogBookQuery<R> query) {
            super(query.name(), true, true);
            this.query = query;
        }

        private LogBookQuery<R> query() {
            return query;
        }
    }

    private <R> void executeCommand(LogBookCommand<R> command) {
        offerOrReject(new CommandOperationItem<R>(command, false));
    }

    private void failIfNotReentrant(String operation) {
        if (dataLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    "Reentrant ORDERED LogBook operation "
                            + operation
                            + " cannot wait for the same Data Lane");
        }
    }

    private void offerOrReject(LogBookOperationItem<?> item) {
        switch (dataLane.offer(item)) {
            case ACCEPTED:
                return;
            case FULL:
                throw new RejectedExecutionException("LogBook Data Lane item queue is full");
            case NOT_RUNNING:
                throw new RejectedExecutionException("LogBook Data Lane item queue is not running");
            default:
                throw new IllegalStateException("Unsupported LogBook Data Lane offer result");
        }
    }

    private <R> R await(
            Future<R> future,
            String operation,
            String operationType,
            long timeoutMillis,
            boolean outcomeUnknownOnTimeout) {

        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            if (outcomeUnknownOnTimeout) {
                throw new IllegalStateException(
                        operation + " timed out after " + timeoutMillis
                                + " ms; final LogBook outcome is unknown",
                        ex);
            }
            throw new IllegalStateException(
                    operation + " timed out after " + timeoutMillis + " ms",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    operation + " was interrupted while waiting for the LogBook result",
                    ex);
        } catch (CancellationException ex) {
            throw new IllegalStateException(
                    operation + " was cancelled because the LogBook Data Lane failed",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            String detail = cause == null ? null : cause.getMessage();
            throw new IllegalStateException(
                    operation
                            + " failed while executing on the LogBook Data Lane"
                            + (detail == null || detail.trim().isEmpty()
                                    ? ""
                                    : ": " + detail.trim()),
                    cause);
        }
    }

    private IllegalStateException rejectedOperation(
            String operation,
            String operationType,
            RejectedExecutionException rejection) {

        String reason = dataLane.isRunning() ? "full" : "not running";
        LOG.warn(
                "TimingNode {} LogBook {} {} rejected because Data Lane is {}",
                timingNodeId().value(),
                operationType,
                operation,
                reason,
                rejection);
        return new IllegalStateException(
                operation + " could not be queued because the LogBook Data Lane is " + reason,
                rejection);
    }

    /** Processes one typed operation on this LogBook's Data Lane. */
    private void processDataLaneItem(LogBookOperationItem<?> item) {

        if (item instanceof CommandOperationItem<?>) {
            processCommandItem((CommandOperationItem<?>) item);
        } else if (item instanceof QueryOperationItem<?>) {
            processQueryItem((QueryOperationItem<?>) item);
        } else {
            throw new IllegalStateException(
                    "Unsupported LogBook operation item " + item.getClass().getName());
        }
    }

    /** Applies one queued command and completes its result holder. */
    private <R> void processCommandItem(CommandOperationItem<R> item) {

        try {
            item.complete(applyCommand(item.command()));
        } catch (Exception ex) {
            item.fail(ex);
            if (!item.callerWaits()) {
                LOG.error(
                        "Queued LogBook command {} failed during Data Lane execution",
                        item.name(),
                        ex);
            }
        } catch (Error error) {
            item.fail(error);
            throw error;
        }
    }

    /** Reads one queued ORDERED query and completes its result holder. */
    private <R> void processQueryItem(QueryOperationItem<R> item) {

        try {
            item.complete(item.query().readOrdered(logBookState));
        } catch (RuntimeException ex) {
            item.fail(ex);
        } catch (Error error) {
            item.fail(error);
            throw error;
        }
    }

    /** Applies one command and publishes either its commit fact or failure fact. */
    private <R> R applyCommand(LogBookCommand<R> command) throws Exception {

        try {
            applyNextWriteTestFailure();

            long headBefore = logBookState.timingDataHead();
            R result = command.apply(logBookOperations);

            if (logBookState.timingDataHead() > headBefore) {
                newDataEntryEvent.emit(logBookState.latestCommittedRecord());
            }
            return result;
        } catch (Exception ex) {
            commandFailedEvent.emit(new LogBookFailure(command.name(), ex));
            throw ex;
        }
    }

    /** Applies and consumes the one-shot engineering/test write failure. */
    private void applyNextWriteTestFailure() throws Exception {

        WriteFailure failure = nextWriteFailure.getAndSet(null);
        if (failure == null) {
            return;
        }

        switch (failure) {
            case FAIL:
                throw new TimingDataPersistence.PersistenceException(
                        "Test hook forced the next LogBook write to fail");
            case TIMEOUT:
                Thread.sleep(commitWaitTimeoutMillis + 100L);
                break;
            default:
                throw new IllegalStateException(
                        "Unsupported LogBook write test failure " + failure);
        }
    }


}
