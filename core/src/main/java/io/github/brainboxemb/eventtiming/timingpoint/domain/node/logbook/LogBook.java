package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventWithMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeDataLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Data Lane component for one TimingNode's committed TimingData.
 *
 * <p>LogBook owns persisted-data recovery plus the Data Lane boundary for ordered
 * commands, ORDERED queries, durable TimingData commit and publication of immutable
 * recovery/read views. Recovery runs when TimingNode.MainTask calls
 * {@link #loadPersistedData()}; LogBook owns no executor. The supplied
 * {@link TimingNodeDataLane} may share a Runtime-owned worker with other logical
 * lanes.</p>
 */
public final class LogBook extends LifecycleComponent implements LogBookAccessor, LogBookTestControl {
    private static final Logger LOG = LoggerFactory.getLogger(LogBook.class);
    public static final long DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS = 5000L;
    public static final long DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS = 2000L;

    private final LogBookState logBookState;
    private final LogBookOperations logBookOperations;
    private final LogBookDataLane dataLane;
    private final LogBookRecoveryView recoveryView;
    private final LogBookMetrics metrics;
    private final long commitWaitTimeoutMillis;
    private final AtomicReference<WriteFailure> nextWriteFailure =
            new AtomicReference<WriteFailure>();

    private final EventWithMetrics<TimingData> newDataEntryEvent;
    private final Event<List<TimingData>> initialDataLoadedEvent = new Event<List<TimingData>>();
    private final Event<LogBookFailure> commandFailedEvent = new Event<LogBookFailure>();

    /** Completed startup-recovery source. */
    public final EventSource<LogBookRecoveryStatus> RECOVERY_STATUS_CHANGED;

    /** Post-commit TimingData source. */
    public final EventSource<TimingData> NEW_DATA_ENTRY;

    /** Failed LogBook command source. */
    public final EventSource<LogBookFailure> COMMAND_FAILED = commandFailedEvent;

    /** Transient persisted-data source for one-time derived-state reconstruction. */
    public final EventSource<List<TimingData>> INITIAL_DATA_LOADED = initialDataLoadedEvent;

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
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            LogBookMetrics metrics,
            TimingNodeDataLane dataLane,
            long commitWaitTimeoutMillis,
            long orderedQueryWaitTimeoutMillis) {

        failIfInvalidArgument(timingNodeId != null, "timingNodeId must not be null");
        failIfInvalidArgument(timingDataPersistence != null, "timingDataPersistence must not be null");
        failIfInvalidArgument(timingDataFactory != null, "timingDataFactory must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");
        failIfInvalidArgument(dataLane != null, "dataLane must not be null");
        failIfInvalidArgument(commitWaitTimeoutMillis > 0L, "commitWaitTimeoutMillis must be positive");
        failIfInvalidArgument(
                orderedQueryWaitTimeoutMillis > 0L,
                "orderedQueryWaitTimeoutMillis must be positive");

        this.logBookState = new LogBookState(timingNodeId);
        this.logBookOperations = new LogBookOperations(
                logBookState,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                monotonicClock,
                metrics);
        dataLane.bindLogBook(this::processDataLaneItem);
        this.dataLane =
                new LogBookDataLane(
                        timingNodeId,
                        dataLane,
                        commitWaitTimeoutMillis,
                        orderedQueryWaitTimeoutMillis);
        this.recoveryView = new LogBookRecoveryView(timingNodeId, this.dataLane);
        this.metrics = metrics;
        this.commitWaitTimeoutMillis = commitWaitTimeoutMillis;
        this.newDataEntryEvent =
                new EventWithMetrics<TimingData>(
                        monotonicClock,
                        metrics.timingDataEventMetrics());
        this.RECOVERY_STATUS_CHANGED = recoveryView.recoveryStatusChangedEvent();
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
     * <p>TimingNode.MainTask starts recovery after child activation. The Data
     * Lane remains stopped until that task completes recovery.</p>
     */
    @Override
    protected void onActivate() {

        /*
         * Normal operations may already queue while TimingNode.MainTask loads
         * persisted state. Data Lane and Task Lane share the same node worker,
         * so those operations cannot execute until the startup task yields.
         */
        dataLane.activate();
    }

    /**
     * Stops new Data Lane work and makes the CURRENT recovery result unavailable.
     *
     * <p>Lane shutdown/drain semantics are owned by {@link TimingNodeDataLane}; LogBook
     * does not implement a second shutdown queue.</p>
     */
    @Override
    protected void onDeactivate() {

        dataLane.deactivate();
        recoveryView.clear();
    }

    /**
     * Processes one command in LogBook order and returns its domain result.
     *
     * @throws IllegalStateException when ordered execution cannot return a result
     */
    public <R> R process(LogBookCommand<R> command) {

        failIfInvalidArgument(command != null, "command must not be null");
        return dataLane.submitCommandAndWait(command);
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
                () -> dataLane.execute(command),
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

        return dataLane.submitQuery(query);
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
                return dataLane.submitQueryAndWait(query);
            default:
                throw new IllegalStateException("Unsupported read consistency " + consistency);
        }
    }

    /**
     * Returns the recovery event through the abstraction-compatible LogBookAccessor contract.
     *
     * <p>Concrete composition uses {@link #RECOVERY_STATUS_CHANGED} directly.</p>
     */
    public EventSource<LogBookRecoveryStatus> recoveryStatusChangedEvent() {

        return RECOVERY_STATUS_CHANGED;
    }

    /**
     * Returns the post-commit event through the abstraction-compatible LogBookAccessor contract.
     *
     * <p>Concrete composition uses {@link #NEW_DATA_ENTRY} directly.</p>
     */
    public EventSource<TimingData> newDataEntryEvent() {

        return NEW_DATA_ENTRY;
    }

    /** Returns the committed TimingData head while already inside LogBook query handling. */
    long currentTimingDataHead() {

        return logBookState.timingDataHead();
    }

    /** Returns the latest safely published CURRENT recovery result. */
    LogBookRecoveryStatus publishedRecoveryStatus() {

        return recoveryView.current();
    }

    /**
     * Loads persisted TimingData as the TimingNode MainTask startup step.
     *
     * <p>The call is intentionally synchronous. TimingNode invokes it from its
     * asynchronous MainTask on the shared node worker. Data Lane operations may
     * queue meanwhile, but cannot execute on that same worker until this startup
     * step returns.</p>
     */
    public LogBookRecoveryStatus loadPersistedData() {
        failIfInactive();
        LOG.info(
                "TimingNode {} persisted TimingData load started",
                timingNodeId().value());

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

            initialDataLoadedEvent.emit(loadResult.records());
        } catch (TimingDataPersistence.PersistenceException | RuntimeException ex) {
            // An inactive LogBook is a cancelled startup flow, not a recovery failure.
            failIfInactive();
            logBookOperations.markTimingDataLoadFailed(ex);
            LOG.error(
                    "Persisted TimingData load failed for {}",
                    timingNodeId().value(),
                    ex);
        }

        failIfInactive();
        LogBookRecoveryStatus status = logBookState.recoveryStatus();
        recoveryView.publishInitial(status);
        return status;
    }

    /** Processes one typed LogBook operation on the shared Data Lane worker. */
    private void processDataLaneItem(LogBookOperationItem<?> item) {

        if (item instanceof LogBookDataLane.CommandOperationItem<?>) {
            processCommandItem((LogBookDataLane.CommandOperationItem<?>) item);
        } else if (item instanceof LogBookDataLane.QueryOperationItem<?>) {
            processQueryItem((LogBookDataLane.QueryOperationItem<?>) item);
        } else {
            throw new IllegalStateException(
                    "Unsupported LogBook operation item " + item.getClass().getName());
        }
    }

    /** Applies one queued command and completes its result holder. */
    private <R> void processCommandItem(LogBookDataLane.CommandOperationItem<R> item) {

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
    private <R> void processQueryItem(LogBookDataLane.QueryOperationItem<R> item) {

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
                newDataEntryEvent.emit(logBookState.latestCommittedTimingData());
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
