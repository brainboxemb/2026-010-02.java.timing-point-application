package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLaneMetrics;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Serial Data Lane owned by one LogBook.
 *
 * <p>The lane contains only {@link LogBookOperationItem} values: normal commands
 * and ORDERED queries. Startup recovery runs on TimingNode's Task Lane and is
 * never admitted here.</p>
 */
public final class LogBookDataLane implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(LogBookDataLane.class);

    /** One queued state-changing LogBook command. */
    static final class CommandOperationItem<R> extends LogBookOperationItem<R> {
        private final LogBookCommand<R> command;

        private CommandOperationItem(
                LogBookCommand<R> command,
                boolean callerWaits) {

            super(command.name(), callerWaits, callerWaits);
            this.command = command;
        }

        LogBookCommand<R> command() {
            return command;
        }
    }

    /** One queued ORDERED LogBook query. */
    static final class QueryOperationItem<R> extends LogBookOperationItem<R> {
        private final LogBookQuery<R> query;

        private QueryOperationItem(LogBookQuery<R> query) {
            super(query.name(), true, true);
            this.query = query;
        }

        LogBookQuery<R> query() {
            return query;
        }
    }

    private final NodeId nodeId;
    private final SerialLane<LogBookOperationItem<?>> lane;
    private final long commitWaitTimeoutMillis;
    private final long orderedQueryWaitTimeoutMillis;

    private Consumer<LogBookOperationItem<?>> processor;

    /**
     * Creates one bounded LogBook Data Lane on the supplied Runtime-owned executor.
     */
    public LogBookDataLane(
            NodeId nodeId,
            int capacity,
            int maxItemsPerRun,
            String laneName,
            Executor executor,
            long commitWaitTimeoutMillis,
            long orderedQueryWaitTimeoutMillis) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(commitWaitTimeoutMillis > 0L, "commitWaitTimeoutMillis must be positive");
        failIfInvalidArgument(
                orderedQueryWaitTimeoutMillis > 0L,
                "orderedQueryWaitTimeoutMillis must be positive");

        this.nodeId = nodeId;
        this.lane =
                new SerialLane<LogBookOperationItem<?>>(
                        capacity,
                        maxItemsPerRun,
                        laneName,
                        executor,
                        this::processItem);
        this.commitWaitTimeoutMillis = commitWaitTimeoutMillis;
        this.orderedQueryWaitTimeoutMillis = orderedQueryWaitTimeoutMillis;
    }

    /** Binds the owning LogBook processor before the lane starts. */
    void bind(Consumer<LogBookOperationItem<?>> processor) {
        failIfInvalidArgument(processor != null, "processor must not be null");
        failIfIllegalState(!lane.isRunning(), "LogBook processor must be bound before Data Lane start");
        failIfIllegalState(this.processor == null, "LogBook Data Lane processor is already bound");
        this.processor = processor;
    }

    /** Starts normal LogBook command/query admission during LogBook activation. */
    void activate() {
        failIfIllegalState(processor != null, "LogBook Data Lane processor is not bound");
        lane.start();
    }

    /** Stops new LogBook work and drains already accepted operations. */
    void deactivate() {
        lane.close();
    }

    /** Queues one fire-and-forget LogBook command operation. */
    <R> void execute(LogBookCommand<R> command) {
        failIfInvalidArgument(command != null, "command must not be null");
        offerOrReject(new CommandOperationItem<R>(command, false));
    }

    /** Returns true only while the current Java thread executes this LogBook Data Lane. */
    public boolean isExecutingOnCurrentThread() {
        return lane.isExecutingOnCurrentThread();
    }

    /** Returns current engineering metrics for this LogBook Data Lane. */
    public SerialLaneMetrics metrics() {
        return lane.metrics();
    }

    /** Queues one result-bearing command operation and waits for its ordered result. */
    <R> R submitCommandAndWait(LogBookCommand<R> command) {
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

    /** Queues one ORDERED query operation and returns its completion without waiting. */
    <R> CompletableFuture<R> submitQuery(LogBookQuery<R> query) {
        failIfInvalidArgument(query != null, "query must not be null");

        QueryOperationItem<R> item = new QueryOperationItem<R>(query);
        try {
            offerOrReject(item);
        } catch (RejectedExecutionException ex) {
            throw rejectedOperation(query.name(), "query", ex);
        }

        return item.result();
    }

    /** Queues one ORDERED query operation and waits for its ordered result. */
    <R> R submitQueryAndWait(LogBookQuery<R> query) {
        failIfInvalidArgument(query != null, "query must not be null");
        failIfNotReentrant(query.name());

        return await(
                submitQuery(query),
                query.name(),
                "query",
                orderedQueryWaitTimeoutMillis,
                false);
    }

    long commitWaitTimeoutMillis() {
        return commitWaitTimeoutMillis;
    }

    private void processItem(LogBookOperationItem<?> item) {
        failIfIllegalState(processor != null, "LogBook Data Lane processor is not bound");
        processor.accept(item);
    }

    private void failIfNotReentrant(String operation) {
        if (lane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    "Reentrant ORDERED LogBook operation "
                            + operation
                            + " cannot wait for the same Data Lane");
        }
    }

    private void offerOrReject(LogBookOperationItem<?> item) {
        SerialLane.OfferResult result = lane.offer(item);

        switch (result) {
            case ACCEPTED:
                return;
            case FULL:
                throw new RejectedExecutionException("LogBook Data Lane item queue is full");
            case NOT_RUNNING:
                throw new RejectedExecutionException("LogBook Data Lane item queue is not running");
            default:
                throw new IllegalStateException("Unsupported Data Lane item queue result " + result);
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
                LOG.error(
                        "TimingNode {} LogBook {} {} failed: "
                                + "reason=TIMEOUT, timeoutMillis={}, outcome=UNKNOWN",
                        nodeId.value(),
                        operationType,
                        operation,
                        timeoutMillis,
                        ex);
                throw new IllegalStateException(
                        operation + " timed out after " + timeoutMillis
                                + " ms; final LogBook outcome is unknown",
                        ex);
            }

            LOG.error(
                    "TimingNode {} LogBook {} {} failed: reason=TIMEOUT, timeoutMillis={}",
                    nodeId.value(),
                    operationType,
                    operation,
                    timeoutMillis,
                    ex);
            throw new IllegalStateException(
                    operation + " timed out after " + timeoutMillis + " ms",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn(
                    "TimingNode {} LogBook {} {} failed: reason=INTERRUPTED",
                    nodeId.value(),
                    operationType,
                    operation,
                    ex);
            throw new IllegalStateException(
                    operation + " was interrupted while waiting for the LogBook result",
                    ex);
        } catch (CancellationException ex) {
            LOG.error(
                    "TimingNode {} LogBook {} {} failed: reason=FAILED, cause=Data Lane cancellation",
                    nodeId.value(),
                    operationType,
                    operation,
                    ex);
            throw new IllegalStateException(
                    operation + " was cancelled because the LogBook Data Lane failed",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            String detail = cause == null ? null : cause.getMessage();
            LOG.error(
                    "TimingNode {} LogBook {} {} failed: reason=FAILED, cause=Data Lane execution",
                    nodeId.value(),
                    operationType,
                    operation,
                    cause == null ? ex : cause);
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

        if (!lane.isRunning()) {
            LOG.warn(
                    "TimingNode {} LogBook {} {} failed: reason=UNAVAILABLE",
                    nodeId.value(),
                    operationType,
                    operation,
                    rejection);
            return new IllegalStateException(
                    operation + " could not be queued because the LogBook Data Lane is not running",
                    rejection);
        }

        LOG.warn(
                "TimingNode {} LogBook {} {} failed: reason=BUSY",
                nodeId.value(),
                operationType,
                operation,
                rejection);
        return new IllegalStateException(
                operation + " could not be queued because the LogBook Data Lane is full",
                rejection);
    }

    @Override
    public void close() {
        lane.close();
    }
}
