package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialQueue;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * LogBook-specific ordered item queue on one shared {@link SerialLane}.
 *
 * <p>The queue contains LogBook command/query items, not Runnable or Callable
 * wrappers. {@link SerialQueue} owns bounded FIFO admission and the one-worker
 * scheduling invariant. This class keeps only LogBook-specific wait, timeout,
 * reentrancy and rejection handling.</p>
 */
final class LogBookDataLane {
    private static final Logger LOG = LoggerFactory.getLogger(LogBookDataLane.class);

    /** Base data carried by every queued LogBook Data Lane item. */
    abstract static class Item<R> {
        private final String name;
        private final CompletableFuture<R> result = new CompletableFuture<R>();
        private final boolean callerWaits;

        private Item(String name, boolean callerWaits) {
            this.name = name;
            this.callerWaits = callerWaits;
        }

        String name() {
            return name;
        }

        Future<R> result() {
            return result;
        }

        boolean callerWaits() {
            return callerWaits;
        }

        void complete(R value) {
            result.complete(value);
        }

        void fail(Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    /** One queued state-changing LogBook command. */
    static final class CommandItem<R> extends Item<R> {
        private final LogBookCommand<R> command;

        private CommandItem(LogBookCommand<R> command, boolean callerWaits) {
            super(command.name(), callerWaits);
            this.command = command;
        }

        LogBookCommand<R> command() {
            return command;
        }
    }

    /** One queued ORDERED LogBook query. */
    static final class QueryItem<R> extends Item<R> {
        private final LogBookQuery<R> query;

        private QueryItem(LogBookQuery<R> query) {
            super(query.name(), true);
            this.query = query;
        }

        LogBookQuery<R> query() {
            return query;
        }
    }

    private final NodeId nodeId;
    private final SerialLane serialLane;
    private final SerialQueue<Item<?>> items;
    private final long commitWaitTimeoutMillis;
    private final long orderedQueryWaitTimeoutMillis;

    /** Creates the Data Lane item queue around one externally composed SerialLane. */
    LogBookDataLane(
            NodeId nodeId,
            SerialLane serialLane,
            long commitWaitTimeoutMillis,
            long orderedQueryWaitTimeoutMillis,
            Consumer<Item<?>> itemProcessor) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(serialLane != null, "serialLane must not be null");
        failIfInvalidArgument(commitWaitTimeoutMillis > 0L, "commitWaitTimeoutMillis must be positive");
        failIfInvalidArgument(
                orderedQueryWaitTimeoutMillis > 0L,
                "orderedQueryWaitTimeoutMillis must be positive");
        failIfInvalidArgument(itemProcessor != null, "itemProcessor must not be null");

        this.nodeId = nodeId;
        this.serialLane = serialLane;
        this.items = new SerialQueue<Item<?>>(serialLane, itemProcessor);
        this.commitWaitTimeoutMillis = commitWaitTimeoutMillis;
        this.orderedQueryWaitTimeoutMillis = orderedQueryWaitTimeoutMillis;
    }

    /**
     * Starts the lane, queues persisted-data loading first, then opens item admission.
     *
     * <p>Any command/query item offered after activation is scheduled behind the
     * already accepted startup runnable, preserving startup recovery ordering.</p>
     */
    void activate(Runnable startupTask) {
        failIfInvalidArgument(startupTask != null, "startupTask must not be null");
        serialLane.start();

        Runnable scheduleStartupAndOpenQueue =
                () -> {
                    serialLane.execute(startupTask);
                    items.start();
                };
        Runnable closeRejectedStartup = serialLane::close;
        SafeExecute.run(
                scheduleStartupAndOpenQueue,
                closeRejectedStartup,
                "Persisted TimingData load could not be scheduled");
    }

    /** Stops new item admission, drains accepted items, then closes the logical lane. */
    void deactivate() {
        items.close();
        serialLane.close();
    }

    /** Queues one fire-and-forget LogBook command item. */
    <R> void execute(LogBookCommand<R> command) {
        failIfInvalidArgument(command != null, "command must not be null");
        offerOrReject(new CommandItem<R>(command, false));
    }

    /** Returns true only while the current Java thread executes this Data Lane. */
    boolean isExecutingOnCurrentThread() {
        return serialLane.isExecutingOnCurrentThread();
    }

    /** Returns the underlying logical lane state for LogBook availability decisions. */
    SerialLane.State state() {
        return serialLane.state();
    }

    /** Returns the terminal logical lane failure, if any. */
    Throwable failure() {
        return serialLane.failure();
    }

    /** Queues one result-bearing command item and waits for its ordered result. */
    <R> R submitCommandAndWait(LogBookCommand<R> command) {
        failIfInvalidArgument(command != null, "command must not be null");
        failIfNotReentrant(command.name());

        CommandItem<R> item = new CommandItem<R>(command, true);
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

    /** Queues one ORDERED query item and waits for its ordered result. */
    <R> R submitQueryAndWait(LogBookQuery<R> query) {
        failIfInvalidArgument(query != null, "query must not be null");
        failIfNotReentrant(query.name());

        QueryItem<R> item = new QueryItem<R>(query);
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

    /** Prevents a caller from waiting for a result that requires the same Data Lane. */
    private void failIfNotReentrant(String operation) {
        if (serialLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    "Reentrant ORDERED TimingNode operation "
                            + operation
                            + " cannot wait for the same Data Lane");
        }
    }

    /** Converts SerialQueue admission outcomes to the existing Data Lane rejection boundary. */
    private void offerOrReject(Item<?> item) {
        SerialQueue.OfferResult result = items.offer(item);

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

    /** Waits for Future completion and reports failures at the owning Data Lane boundary. */
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
                throw new IllegalStateException(operation + " timed out after " + timeoutMillis
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
            throw new IllegalStateException(operation + " timed out after " + timeoutMillis + " ms",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn(
                    "TimingNode {} LogBook {} {} failed: reason=INTERRUPTED",
                    nodeId.value(),
                    operationType,
                    operation,
                    ex);
            throw new IllegalStateException(operation + " was interrupted while waiting for the TimingNode result",
                    ex);
        } catch (CancellationException ex) {
            LOG.error(
                    "TimingNode {} LogBook {} {} failed: reason=FAILED, cause=Data Lane cancellation",
                    nodeId.value(),
                    operationType,
                    operation,
                    ex);
            throw new IllegalStateException(operation + " was cancelled because the TimingNode Data Lane failed",
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
            throw new IllegalStateException(operation
                            + " failed while executing on the TimingNode Data Lane"
                            + (detail == null || detail.trim().isEmpty() ? "" : ": " + detail.trim()),
                    cause);
        }
    }

    /** Reports lane rejection and converts it to ordinary failed execution. */
    private IllegalStateException rejectedOperation(
            String operation,
            String operationType,
            RejectedExecutionException rejection) {

        if (serialLane.state() == SerialLane.State.FAILED) {
            Throwable cause = serialLane.failure() == null ? rejection : serialLane.failure();
            LOG.error(
                    "TimingNode {} LogBook {} {} failed: reason=FAILED, cause=Data Lane failure",
                    nodeId.value(),
                    operationType,
                    operation,
                    cause);
            return new IllegalStateException(operation + " could not run because the TimingNode Data Lane failed",
                    cause);
        }
        if (serialLane.state() != SerialLane.State.RUNNING) {
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
