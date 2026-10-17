package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeDataLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * LogBook-specific adapter onto the shared typed TimingNode Data Lane.
 *
 * <p>Commands, queries and startup recovery are {@link LogBookOperationItem}
 * values. This class owns only LogBook-specific admission errors, ordered waits,
 * reentrancy checks and timeouts.</p>
 */
final class LogBookDataLane {
    private static final Logger LOG = LoggerFactory.getLogger(LogBookDataLane.class);

    /** First LogBook operation admitted when the Data Lane starts. */
    static final class StartupOperationItem extends LogBookOperationItem<Void> {
        private StartupOperationItem() {
            super("loadPersistedData", false, false);
        }
    }

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
    private final TimingNodeDataLane dataLane;
    private final long commitWaitTimeoutMillis;
    private final long orderedQueryWaitTimeoutMillis;

    LogBookDataLane(
            NodeId nodeId,
            TimingNodeDataLane dataLane,
            long commitWaitTimeoutMillis,
            long orderedQueryWaitTimeoutMillis) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(dataLane != null, "dataLane must not be null");
        failIfInvalidArgument(commitWaitTimeoutMillis > 0L, "commitWaitTimeoutMillis must be positive");
        failIfInvalidArgument(
                orderedQueryWaitTimeoutMillis > 0L,
                "orderedQueryWaitTimeoutMillis must be positive");

        this.nodeId = nodeId;
        this.dataLane = dataLane;
        this.commitWaitTimeoutMillis = commitWaitTimeoutMillis;
        this.orderedQueryWaitTimeoutMillis = orderedQueryWaitTimeoutMillis;
    }

    /** Starts the Data Lane with persisted-data recovery as its first typed item. */
    void activate() {
        SerialLane.OfferResult result =
                dataLane.start(new StartupOperationItem());
        if (result != SerialLane.OfferResult.ACCEPTED) {
            dataLane.close();
            throw new IllegalStateException(
                    "Persisted TimingData load could not be queued: " + result);
        }
    }

    /** Stops new Data Lane work and drains already accepted items. */
    void deactivate() {
        dataLane.close();
    }

    /** Queues one fire-and-forget LogBook command operation. */
    <R> void execute(LogBookCommand<R> command) {
        failIfInvalidArgument(command != null, "command must not be null");
        offerOrReject(new CommandOperationItem<R>(command, false));
    }

    /** Returns true only while the current Java thread executes this Data Lane. */
    boolean isExecutingOnCurrentThread() {
        return dataLane.isExecutingOnCurrentThread();
    }

    SerialLane.State state() {
        return dataLane.state();
    }

    Throwable failure() {
        return dataLane.failure();
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

    /** Queues one ORDERED query operation and waits for its ordered result. */
    <R> R submitQueryAndWait(LogBookQuery<R> query) {
        failIfInvalidArgument(query != null, "query must not be null");
        failIfNotReentrant(query.name());

        QueryOperationItem<R> item = new QueryOperationItem<R>(query);
        try {
            offerOrReject(item);
        } catch (RejectedExecutionException ex) {
            throw rejectedOperation(query.name(), "query", ex);
        }

        return await(
                item.result(),
                query.name(),
                "query",
                orderedQueryWaitTimeoutMillis,
                false);
    }

    private void failIfNotReentrant(String operation) {
        if (dataLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    "Reentrant ORDERED TimingNode operation "
                            + operation
                            + " cannot wait for the same Data Lane");
        }
    }

    private void offerOrReject(LogBookOperationItem<?> item) {
        SerialLane.OfferResult result = dataLane.offer(item);

        switch (result) {
            case ACCEPTED:
                return;
            case FULL:
                throw new RejectedExecutionException("TimingNode Data Lane item queue is full");
            case NOT_RUNNING:
                throw new RejectedExecutionException("TimingNode Data Lane item queue is not running");
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
                                + " ms; final TimingNode outcome is unknown",
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
                    operation + " was interrupted while waiting for the TimingNode result",
                    ex);
        } catch (CancellationException ex) {
            LOG.error(
                    "TimingNode {} LogBook {} {} failed: reason=FAILED, cause=Data Lane cancellation",
                    nodeId.value(),
                    operationType,
                    operation,
                    ex);
            throw new IllegalStateException(
                    operation + " was cancelled because the TimingNode Data Lane failed",
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
                            + " failed while executing on the TimingNode Data Lane"
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

        if (dataLane.state() == SerialLane.State.FAILED) {
            Throwable cause =
                    dataLane.failure() == null
                            ? rejection
                            : dataLane.failure();
            LOG.error(
                    "TimingNode {} LogBook {} {} failed: reason=FAILED, cause=Data Lane failure",
                    nodeId.value(),
                    operationType,
                    operation,
                    cause);
            return new IllegalStateException(
                    operation + " could not run because the TimingNode Data Lane failed",
                    cause);
        }

        if (!dataLane.isRunning()) {
            LOG.warn(
                    "TimingNode {} LogBook {} {} failed: reason=UNAVAILABLE",
                    nodeId.value(),
                    operationType,
                    operation,
                    rejection);
            return new IllegalStateException(
                    operation + " could not be queued because the TimingNode Data Lane is not running",
                    rejection);
        }

        LOG.warn(
                "TimingNode {} LogBook {} {} failed: reason=BUSY",
                nodeId.value(),
                operationType,
                operation,
                rejection);
        return new IllegalStateException(
                operation + " could not be queued because the TimingNode Data Lane is full",
                rejection);
    }
}
