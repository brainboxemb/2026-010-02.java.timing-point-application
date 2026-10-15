package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventWithMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.List;

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
public final class LogBook implements LogBookAccessor {
    private static final Logger LOG = LoggerFactory.getLogger(LogBook.class);
    public static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 2000L;

    private final LogBookState logBookState;
    private final LogBookOperations logBookOperations;
    private final LogBookDataLane dataLane;
    private final LogBookStatus logBookStatus;
    private final LogBookMetrics metrics;

    private final EventWithMetrics<TimingData> newDataEntryEvent;
    private final Event<List<TimingData>> initialDataLoadedEvent = new Event<List<TimingData>>();

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
        this.logBookStatus = new LogBookStatus(timingNodeId, dataLane);
        this.metrics = metrics;
        this.newDataEntryEvent =
                new EventWithMetrics<TimingData>(
                        monotonicClock,
                        metrics.timingDataEventMetrics());
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

        dataLane.activate(this::loadPersistedData);
    }

    /**
     * Stops new Data Lane work and makes CURRENT status unavailable.
     *
     * <p>Lane shutdown/drain semantics are owned by {@link SerialLane}; LogBook
     * does not implement a second shutdown queue.</p>
     */
    public void deactivate() {

        dataLane.deactivate();
        logBookStatus.clear();
    }

    /**
     * Processes one command in LogBook order and returns its domain result.
     */
    public <R> R process(LogBookCommand<R> command) {

        checkArgument(command != null, "command must not be null");
        return dataLane.submitAndWait(() -> applyCommand(command), command.name());
    }

    /**
     * Executes one fire-and-forget command asynchronously.
     *
     * <p>The method follows Executor-style semantics: it returns after the
     * command is queued and propagates RejectedExecutionException immediately
     * when the bounded SerialLane cannot queue it.</p>
     */
    public void execute(LogBookCommand<?> command) {

        checkArgument(command != null, "command must not be null");
        Runnable queuedCommand = () -> executeQueuedCommand(command);
        dataLane.execute(queuedCommand);
    }

    /**
     * Processes an ORDERED query and waits until earlier queued Data Lane work
     * has been processed.
     */
    public <R> R process(LogBookQuery<R> query) {

        return process(query, LogBookQuery.ReadConsistency.ORDERED);
    }

    /**
     * Processes either the directly published CURRENT view or an ORDERED Data
     * Lane view, according to the requested consistency.
     */
    public <R> R process(LogBookQuery<R> query, LogBookQuery.ReadConsistency consistency) {

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

    /** Publishes status changes synchronously on the Data Lane after commit. */
    public EventSource<Status> statusChangedEvent() {

        return logBookStatus.statusChangedEvent();
    }

    /** Publishes each newly committed TimingData record on the Data Lane. */
    public EventSource<TimingData> newDataEntryEvent() {

        return newDataEntryEvent;
    }

    /**
     * Publishes transient immutable startup history for one-time derived-state reconstruction.
     *
     * <p>The list comes directly from immutable TimingDataPersistence.LoadResult data.
     * Consumers must process and release it rather than retain a second full LogBook history.</p>
     */
    public EventSource<List<TimingData>> initialDataLoadedEvent() {

        return initialDataLoadedEvent;
    }

    /** Returns the committed TimingData head while already inside LogBook query handling. */
    long currentTimingDataHead() {

        return logBookState.timingDataHead();
    }

    /** Returns the latest safely published CURRENT status. */
    Status publishedStatus() {

        return logBookStatus.current();
    }

    /** Loads persisted history, then publishes initial status on either outcome. */
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

            initialDataLoadedEvent.emit(loadResult.records());
        } catch (TimingDataPersistence.PersistenceException | RuntimeException ex) {

            logBookOperations.markTimingDataLoadFailed(ex);
            LOG.error(
                    "Persisted TimingData load failed for {}; TimingNode remains available in ERROR state",
                    timingNodeId().value(),
                    ex);
        }

        // Both loaded and ERROR states become visible once the startup attempt has finished.
        logBookStatus.publishInitial(logBookState.status());
    }

    /** Executes one previously queued fire-and-forget command and contains its later failure. */
    private <R> void executeQueuedCommand(LogBookCommand<R> command) {

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
    private <R> R applyCommand(LogBookCommand<R> command) throws Exception {

        Status before = logBookState.status();
        int committedBefore = logBookState.committedTimingDataCount();

        R result = command.apply(logBookOperations);

        Status after = logBookState.status();
        logBookStatus.publishCurrent(after);

        int committedAfter = logBookState.committedTimingDataCount();
        if (committedAfter > committedBefore) {
            newDataEntryEvent.emit(logBookState.latestCommittedTimingData());
        }

        logBookStatus.publishChanged(before, after);
        return result;
    }

}
