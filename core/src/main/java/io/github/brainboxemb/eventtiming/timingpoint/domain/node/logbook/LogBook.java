package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventWithMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Data Lane component for one TimingNode's committed timing history.
 *
 * <p>LogBook is the single execution boundary for ordered commands,
 * ORDERED queries, persisted-data startup loading, durable TimingData commit and publication of immutable
 * recovery/history views. It owns no physical thread: the supplied
 * {@link SerialLane} may share a Runtime-owned worker with other logical
 * lanes.</p>
 */
public final class LogBook extends LifecycleComponent implements LogBookAccessor {
    private static final Logger LOG = LoggerFactory.getLogger(LogBook.class);
    public static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 2000L;

    private final LogBookState logBookState;
    private final LogBookOperations logBookOperations;
    private final LogBookDataLane dataLane;
    private final LogBookRecoveryView recoveryView;
    private final LogBookMetrics metrics;

    private final EventWithMetrics<TimingData> newDataEntry;
    private final Event<List<TimingData>> initialDataLoaded = new Event<List<TimingData>>();

    /** Completed startup-recovery source. */
    public final EventSource<LogBookRecoveryStatus> RECOVERY_STATUS_CHANGED;

    /** Post-commit TimingData source. */
    public final EventSource<TimingData> NEW_DATA_ENTRY;

    /** Transient startup-history source for one-time derived-state reconstruction. */
    public final EventSource<List<TimingData>> INITIAL_DATA_LOADED = initialDataLoaded;

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

        this.logBookState = new LogBookState(timingNodeId);
        this.logBookOperations = new LogBookOperations(
                logBookState,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                monotonicClock,
                metrics);
        this.dataLane = new LogBookDataLane(serialLane, operationTimeoutMillis);
        this.recoveryView = new LogBookRecoveryView(timingNodeId, dataLane);
        this.metrics = metrics;
        this.newDataEntry =
                new EventWithMetrics<TimingData>(
                        monotonicClock,
                        metrics.timingDataEventMetrics());
        this.RECOVERY_STATUS_CHANGED = recoveryView.recoveryStatusChangedEvent();
        this.NEW_DATA_ENTRY = newDataEntry;
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
     * <p>The call does not wait for file I/O. CURRENT recovery status remains
     * unavailable until that first ordered task has completed startup recovery.</p>
     */
    @Override
    protected void onActivate() {

        dataLane.activate(this::loadPersistedData);
    }

    /**
     * Stops new Data Lane work and makes the CURRENT recovery result unavailable.
     *
     * <p>Lane shutdown/drain semantics are owned by {@link SerialLane}; LogBook
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
     * @throws OperationException when the ordered operation cannot return its normal result
     */
    public <R> R process(LogBookCommand<R> command) throws OperationException {

        checkArgument(command != null, "command must not be null");
        return dataLane.submitAndWait(() -> applyCommand(command), command.name());
    }

    /**
     * Executes one fire-and-forget command asynchronously.
     *
     * <p>The method follows Executor-style semantics: it returns after the
     * command is queued and propagates RejectedExecutionException immediately
     * when the bounded SerialLane cannot queue it.</p>
     *
     * @throws RejectedExecutionException when the Data Lane cannot queue the command
     */
    public void execute(LogBookCommand<?> command) throws RejectedExecutionException {

        checkArgument(command != null, "command must not be null");
        Runnable queuedCommand = () -> executeQueuedCommand(command);
        dataLane.execute(queuedCommand);
    }

    /**
     * Processes an ORDERED query and waits until earlier queued Data Lane work
     * has been processed.
     *
     * @throws OperationException when the ordered query cannot return its normal result
     */
    public <R> R process(LogBookQuery<R> query) throws OperationException {

        return process(query, LogBookQuery.ReadConsistency.ORDERED);
    }

    /**
     * Processes either the directly published CURRENT view or an ORDERED Data
     * Lane view, according to the requested consistency.
     *
     * @throws OperationException when the requested query cannot return its normal result
     */
    public <R> R process(LogBookQuery<R> query, LogBookQuery.ReadConsistency consistency)
            throws OperationException {

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
                if (dataLane.isExecutingOnCurrentThread()) {
                    return query.readOrdered(logBookState);
                }
                return dataLane.submitAndWait(() -> query.readOrdered(logBookState), query.name());
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

    /** Loads persisted history, then publishes the recovery result on either outcome. */
    private void loadPersistedData() {

        LOG.info("TimingNode {} persisted TimingData load started", timingNodeId().value());

        try {
            TimingDataPersistence.LoadResult loadResult = logBookOperations.loadPersistedTimingData();
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

            initialDataLoaded.emit(loadResult.records());
        } catch (TimingDataPersistence.PersistenceException | RuntimeException ex) {

            logBookOperations.markTimingDataLoadFailed(ex);
            LOG.error(
                    "Persisted TimingData load failed for {}",
                    timingNodeId().value(),
                    ex);
        }

        recoveryView.publishInitial(logBookState.recoveryStatus());
    }

    /** Executes one previously queued fire-and-forget command and contains its later failure. */
    private <R> void executeQueuedCommand(LogBookCommand<R> command) {

        try {
            applyCommand(command);
        } catch (Exception ex) {

            LOG.warn(
                    "Queued LogBook command {} failed during Data Lane execution",
                    command.name(),
                    ex);
        }
    }

    /** Applies one command and publishes newly committed TimingData. */
    private <R> R applyCommand(LogBookCommand<R> command) throws Exception {

        long headBefore = logBookState.timingDataHead();
        R result = command.apply(logBookOperations);

        if (logBookState.timingDataHead() > headBefore) {
            newDataEntry.emit(logBookState.latestCommittedTimingData());
        }
        return result;
    }

}
