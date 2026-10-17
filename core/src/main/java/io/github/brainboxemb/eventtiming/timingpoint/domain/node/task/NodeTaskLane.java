package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Node Task Lane execution adapter around one shared {@link SerialLane}.
 *
 * <p>The underlying SerialLane still owns FIFO ordering, bounded admission and
 * queue execution. NodeTaskLane owns only the node-task-specific lifecycle,
 * current-thread invariant and result-bearing wait handling used by
 * NodeService.</p>
 */
final class NodeTaskLane {
    private final SerialLane serialLane;
    private final long operationTimeoutMillis;

    /** Creates the Task Lane adapter around one externally composed SerialLane. */
    NodeTaskLane(SerialLane serialLane, long operationTimeoutMillis) {

        failIfInvalidArgument(serialLane != null, "serialLane must not be null");
        failIfInvalidArgument(operationTimeoutMillis > 0L, "operationTimeoutMillis must be positive");
        this.serialLane = serialLane;
        this.operationTimeoutMillis = operationTimeoutMillis;
    }

    /** Starts the underlying logical Task Lane. */
    void activate() {

        serialLane.start();
    }

    /** Closes the underlying logical Task Lane. */
    void deactivate() {

        serialLane.close();
    }

    /** Queues one non-blocking Task Lane operation using normal rejection semantics. */
    void execute(Runnable operation) {

        failIfInvalidArgument(operation != null, "operation must not be null");
        serialLane.execute(operation);
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

    /** Submits one result-bearing operation and waits for its ordered result. */
    <R> R submitAndWait(Callable<R> work, String operation) {

        failIfInvalidArgument(work != null, "work must not be null");
        failIfInvalidArgument(operation != null && !operation.trim().isEmpty(), "operation must not be blank");

        if (serialLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    "Reentrant ORDERED TimingNode operation "
                            + operation
                            + " cannot wait for the same Task Lane");
        }

        final Future<R> future;
        try {
            future = serialLane.submit(work);
        } catch (RejectedExecutionException ex) {
            throw rejectedOperation(operation, ex);
        }
        return await(future, operation);
    }

    /** Waits for Future completion and aborts when Task Lane execution cannot return a result. */
    private <R> R await(Future<R> future, String operation) {

        try {
            return future.get(operationTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(operation + " timed out on the TimingNode Task Lane",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " was interrupted on the TimingNode Task Lane",
                    ex);
        } catch (CancellationException ex) {
            throw new IllegalStateException(operation + " was cancelled on the TimingNode Task Lane",
                    ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException(operation + " failed on the TimingNode Task Lane",
                    ex.getCause());
        }
    }

    /** Converts Task Lane rejection to ordinary failed execution. */
    private IllegalStateException rejectedOperation(String operation, RejectedExecutionException rejection) {

        if (serialLane.state() == SerialLane.State.FAILED) {
            return new IllegalStateException(operation + " could not run because the TimingNode Task Lane failed",
                    serialLane.failure() == null ? rejection : serialLane.failure());
        }
        if (serialLane.state() != SerialLane.State.RUNNING) {
            return new IllegalStateException(operation + " could not be queued because the TimingNode Task Lane is not running",
                    rejection);
        }
        return new IllegalStateException(operation + " could not be queued because the TimingNode Task Lane is full",
                rejection);
    }
}
