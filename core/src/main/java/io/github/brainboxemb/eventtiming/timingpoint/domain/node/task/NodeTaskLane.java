package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

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

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * NodeService Task Lane item queue on one shared {@link SerialLane}.
 *
 * <p>The queue contains NodeService-owned items, not Runnable or Callable
 * wrappers. {@link SerialQueue} owns bounded FIFO admission and the one-worker
 * scheduling invariant. NodeTaskLane owns lifecycle, reentrancy and result wait
 * handling.</p>
 */
final class NodeTaskLane {

    /** Base data carried by one queued NodeService Task Lane item. */
    abstract static class Item<R> {
        private final String name;
        private final CompletableFuture<R> result;

        /** Creates one fire-and-forget item. */
        Item(String name) {
            this(name, false);
        }

        /** Creates one item and optionally gives it a caller-visible result. */
        Item(String name, boolean resultBearing) {
            failIfInvalidArgument(name != null && !name.trim().isEmpty(), "name must not be blank");
            this.name = name;
            this.result = resultBearing ? new CompletableFuture<R>() : null;
        }

        String name() {
            return name;
        }

        Future<R> result() {
            failIfIllegalState(result != null, "Task Lane item %s has no result", name);
            return result;
        }

        void complete(R value) {
            if (result != null) {
                result.complete(value);
            }
        }

        void fail(Throwable failure) {
            if (result != null) {
                result.completeExceptionally(failure);
            }
        }
    }

    private final SerialLane serialLane;
    private final SerialQueue<Item<?>> items;
    private final long operationTimeoutMillis;

    /** Creates the Task Lane item queue around one externally composed SerialLane. */
    NodeTaskLane(
            SerialLane serialLane,
            long operationTimeoutMillis,
            Consumer<Item<?>> itemProcessor) {

        failIfInvalidArgument(serialLane != null, "serialLane must not be null");
        failIfInvalidArgument(operationTimeoutMillis > 0L, "operationTimeoutMillis must be positive");
        failIfInvalidArgument(itemProcessor != null, "itemProcessor must not be null");

        this.serialLane = serialLane;
        this.items = new SerialQueue<Item<?>>(serialLane, itemProcessor);
        this.operationTimeoutMillis = operationTimeoutMillis;
    }

    /** Starts the logical Task Lane and opens item admission. */
    void activate() {
        serialLane.start();
        items.start();
    }

    /** Stops item admission, drains accepted items and closes the logical Task Lane. */
    void deactivate() {
        items.close();
        serialLane.close();
    }

    /** Queues one fire-and-forget Task Lane item. */
    void execute(Item<?> item) {
        failIfInvalidArgument(item != null, "item must not be null");
        offerOrReject(item);
    }

    /** Reports whether the current Java thread owns this Task Lane turn. */
    boolean isExecutingOnCurrentThread() {
        return serialLane.isExecutingOnCurrentThread();
    }

    /** Fails fast when mutable Task Lane state is accessed outside its lane turn. */
    void requireCurrentThread(String operation) {
        failIfInvalidArgument(operation != null && !operation.trim().isEmpty(), "operation must not be blank");
        if (!serialLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(operation + " requires the TimingNode Task Lane");
        }
    }

    /** Queues one result-bearing Task Lane item and waits for its ordered result. */
    <R> R submitAndWait(Item<R> item) {
        failIfInvalidArgument(item != null, "item must not be null");

        if (serialLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    "Reentrant ORDERED TimingNode operation "
                            + item.name()
                            + " cannot wait for the same Task Lane");
        }

        try {
            offerOrReject(item);
        } catch (RejectedExecutionException ex) {
            throw rejectedOperation(item.name(), ex);
        }
        return await(item.result(), item.name());
    }

    /** Converts SerialQueue admission outcomes to the existing Task Lane rejection boundary. */
    private void offerOrReject(Item<?> item) {
        SerialQueue.OfferResult result = items.offer(item);

        switch (result) {
            case ACCEPTED:
                return;
            case FULL:
                throw new RejectedExecutionException("TimingNode Task Lane item queue is full");
            case NOT_RUNNING:
                throw new RejectedExecutionException("TimingNode Task Lane item queue is not running");
            default:
                throw new IllegalStateException("Unsupported Task Lane item queue result " + result);
        }
    }

    /** Waits for Future completion and aborts when Task Lane execution cannot return a result. */
    private <R> R await(Future<R> future, String operation) {
        try {
            return future.get(operationTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(operation + " timed out on the TimingNode Task Lane", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " was interrupted on the TimingNode Task Lane", ex);
        } catch (CancellationException ex) {
            throw new IllegalStateException(operation + " was cancelled on the TimingNode Task Lane", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException(operation + " failed on the TimingNode Task Lane", ex.getCause());
        }
    }

    /** Converts Task Lane rejection to ordinary failed execution. */
    private IllegalStateException rejectedOperation(
            String operation,
            RejectedExecutionException rejection) {

        if (serialLane.state() == SerialLane.State.FAILED) {
            return new IllegalStateException(
                    operation + " could not run because the TimingNode Task Lane failed",
                    serialLane.failure() == null ? rejection : serialLane.failure());
        }
        if (serialLane.state() != SerialLane.State.RUNNING) {
            return new IllegalStateException(
                    operation + " could not be queued because the TimingNode Task Lane is not running",
                    rejection);
        }
        return new IllegalStateException(
                operation + " could not be queued because the TimingNode Task Lane is full",
                rejection);
    }
}
