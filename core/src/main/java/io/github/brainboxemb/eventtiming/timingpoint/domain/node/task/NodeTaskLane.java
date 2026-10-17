package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTaskLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * NodeService-specific adapter onto the shared typed {@link TimingNodeTaskLane}.
 *
 * <p>The shared Task Lane owns FIFO admission and the one-worker scheduling
 * invariant. NodeTaskLane owns NodeService result waits, timeout handling and
 * same-lane reentrancy checks.</p>
 */
final class NodeTaskLane {

    /** One caller-owned value that can be queued on the NodeService Task Lane. */
    interface Item extends NodeServiceTaskItem {
    }

    /** Task Lane item whose caller waits for a later result. */
    interface ResultItem<R> extends Item {
        Future<R> result();
    }

    private final TimingNodeTaskLane taskLane;
    private final long operationTimeoutMillis;

    /** Creates the NodeService adapter on one shared typed Task Lane. */
    NodeTaskLane(
            TimingNodeTaskLane taskLane,
            long operationTimeoutMillis,
            java.util.function.Consumer<Item> itemProcessor) {

        failIfInvalidArgument(taskLane != null, "taskLane must not be null");
        failIfInvalidArgument(operationTimeoutMillis > 0L, "operationTimeoutMillis must be positive");
        failIfInvalidArgument(itemProcessor != null, "itemProcessor must not be null");

        this.taskLane = taskLane;
        this.operationTimeoutMillis = operationTimeoutMillis;
        taskLane.bindNodeService(item -> itemProcessor.accept((Item) item));
    }

    /** Starts the logical Task Lane and opens item admission. */
    void activate() {
        taskLane.start();
    }

    /** Stops item admission, drains accepted items and closes the logical Task Lane. */
    void deactivate() {
        taskLane.close();
    }

    /** Queues one fire-and-forget Task Lane item. */
    void execute(Item item) {
        failIfInvalidArgument(item != null, "item must not be null");
        offerOrReject(item);
    }

    /** Reports whether the current Java thread owns this Task Lane turn. */
    boolean isExecutingOnCurrentThread() {
        return taskLane.isExecutingOnCurrentThread();
    }

    /** Fails fast when mutable Task Lane state is accessed outside its lane turn. */
    void requireCurrentThread(String operation) {
        failIfInvalidArgument(operation != null && !operation.trim().isEmpty(), "operation must not be blank");
        if (!taskLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(operation + " requires the TimingNode Task Lane");
        }
    }

    /** Queues one result-bearing Task Lane item and waits for its ordered result. */
    <R> R submitAndWait(ResultItem<R> item) {
        failIfInvalidArgument(item != null, "item must not be null");

        if (taskLane.isExecutingOnCurrentThread()) {
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

    /** Converts typed Task Lane admission outcomes to the NodeService rejection boundary. */
    private void offerOrReject(Item item) {
        SerialLane.OfferResult result = taskLane.offer(item);

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

        if (taskLane.state() == SerialLane.State.FAILED) {
            return new IllegalStateException(
                    operation + " could not run because the TimingNode Task Lane failed",
                    taskLane.failure() == null ? rejection : taskLane.failure());
        }
        if (!taskLane.isRunning()) {
            return new IllegalStateException(
                    operation + " could not be queued because the TimingNode Task Lane is not running",
                    rejection);
        }
        return new IllegalStateException(
                operation + " could not be queued because the TimingNode Task Lane is full",
                rejection);
    }
}
