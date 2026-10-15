package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Node Task Lane execution adapter around one shared {@link SerialLane}.
 *
 * <p>The underlying SerialLane still owns FIFO ordering, bounded admission and
 * queue execution. NodeTaskLane owns only the node-task-specific lifecycle,
 * current-thread invariant and result-bearing wait/error mapping used by
 * NodeService.</p>
 */
final class NodeTaskLane {
    private final SerialLane serialLane;
    private final long operationTimeoutMillis;

    /** Creates the Task Lane adapter around one externally composed SerialLane. */
    NodeTaskLane(SerialLane serialLane, long operationTimeoutMillis) {

        checkArgument(serialLane != null, "serialLane must not be null");
        checkArgument(operationTimeoutMillis > 0L, "operationTimeoutMillis must be positive");
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

        checkArgument(operation != null, "operation must not be null");
        serialLane.execute(operation);
    }

    /** Reports whether the current Java thread owns this Task Lane turn. */
    boolean isExecutingOnCurrentThread() {

        return serialLane.isExecutingOnCurrentThread();
    }

    /** Fails fast when mutable Task Lane state is accessed outside its lane turn. */
    void requireCurrentThread(String operation) {

        checkArgument(operation != null && !operation.trim().isEmpty(), "operation must not be blank");
        if (!serialLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(operation + " requires the TimingNode Task Lane");
        }
    }

    /** Submits one result-bearing operation and waits for its ordered result. */
    <R> R submitAndWait(Callable<R> work, String operation) {

        checkArgument(work != null, "work must not be null");
        checkArgument(operation != null && !operation.trim().isEmpty(), "operation must not be blank");

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

    /** Converts Future completion into the stable Task Lane operation exception contract. */
    private <R> R await(Future<R> future, String operation) {

        try {
            return future.get(operationTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new OperationException(
                    OperationException.Reason.TIMEOUT,
                    operation + " timed out on the TimingNode Task Lane",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OperationException(
                    OperationException.Reason.INTERRUPTED,
                    operation + " was interrupted on the TimingNode Task Lane",
                    ex);
        } catch (CancellationException ex) {
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " was cancelled on the TimingNode Task Lane",
                    ex);
        } catch (ExecutionException ex) {
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " failed on the TimingNode Task Lane",
                    ex.getCause());
        }
    }

    /** Maps Task Lane rejection to the stable result-bearing operation categories. */
    private OperationException rejectedOperation(String operation, RejectedExecutionException rejection) {

        if (serialLane.state() == SerialLane.State.FAILED) {
            return new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " could not run because the TimingNode Task Lane failed",
                    serialLane.failure() == null ? rejection : serialLane.failure());
        }
        if (serialLane.state() != SerialLane.State.RUNNING) {
            return new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    operation + " could not be queued because the TimingNode Task Lane is not running",
                    rejection);
        }
        return new OperationException(
                OperationException.Reason.BUSY,
                operation + " could not be queued because the TimingNode Task Lane is full",
                rejection);
    }
}
