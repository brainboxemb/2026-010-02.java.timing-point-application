package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
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
 * LogBook-specific execution boundary around the shared {@link SerialLane}.
 *
 * <p>This class does not implement another queue or thread model. The supplied
 * SerialLane remains the owner of FIFO ordering, bounded admission and lane
 * lifecycle. LogBookDataLane owns only the LogBook-specific wait/reentrancy and
 * OperationException mapping used by result-bearing Data Lane operations.</p>
 */
final class LogBookDataLane {
    private final SerialLane serialLane;
    private final long operationTimeoutMillis;

    /** Creates the LogBook Data Lane adapter around one externally composed SerialLane. */
    LogBookDataLane(SerialLane serialLane, long operationTimeoutMillis) {
        checkArgument(serialLane != null, "serialLane must not be null");
        checkArgument(operationTimeoutMillis > 0L, "operationTimeoutMillis must be positive");
        this.serialLane = serialLane;
        this.operationTimeoutMillis = operationTimeoutMillis;
    }

    /**
     * Starts the lane and schedules startup work as its first ordered task.
     *
     * <p>If the first task cannot be admitted, the lane is closed again so the
     * component cannot remain half-activated.</p>
     */
    void activate(Runnable startupTask) {
        checkArgument(startupTask != null, "startupTask must not be null");
        serialLane.start();

        Runnable scheduleStartup = () -> serialLane.execute(startupTask);
        Runnable closeRejectedStartup = serialLane::close;
        SafeExecute.run(
                scheduleStartup,
                closeRejectedStartup,
                "Persisted TimingData load could not be scheduled");
    }

    /** Stops accepting work and drains/closes the underlying logical lane. */
    void deactivate() {
        serialLane.close();
    }

    /** Queues one fire-and-forget operation using normal SerialLane rejection semantics. */
    void execute(Runnable operation) {
        checkArgument(operation != null, "operation must not be null");
        serialLane.execute(operation);
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

    /** Submits one result-bearing operation and waits for its ordered result. */
    <R> R submitAndWait(Callable<R> work, String operation) {
        checkArgument(work != null, "work must not be null");
        checkArgument(operation != null && !operation.trim().isEmpty(), "operation must not be blank");

        if (serialLane.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    "Reentrant ORDERED TimingNode operation "
                            + operation
                            + " cannot wait for the same Data Lane");
        }

        final Future<R> future;
        try {
            future = serialLane.submit(work);
        } catch (RejectedExecutionException ex) {
            throw rejectedOperation(operation, ex);
        }
        return await(future, operation);
    }

    /** Converts Future completion into the stable LogBook operation exception contract. */
    private <R> R await(Future<R> future, String operation) {
        try {
            return future.get(operationTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new OperationException(
                    OperationException.Reason.TIMEOUT,
                    operation + " timed out; final TimingNode outcome is unknown",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OperationException(
                    OperationException.Reason.INTERRUPTED,
                    operation + " was interrupted while waiting for the TimingNode result",
                    ex);
        } catch (CancellationException ex) {
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " was cancelled because the TimingNode Data Lane failed",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            String detail = cause == null ? null : cause.getMessage();
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    operation
                            + " failed while executing on the TimingNode Data Lane"
                            + (detail == null || detail.trim().isEmpty() ? "" : ": " + detail.trim()),
                    cause);
        }
    }

    /** Maps lane rejection to the result-bearing LogBook operation categories. */
    private OperationException rejectedOperation(
            String operation, RejectedExecutionException rejection) {
        if (serialLane.state() == SerialLane.State.FAILED) {
            return new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " could not run because the TimingNode Data Lane failed",
                    serialLane.failure() == null ? rejection : serialLane.failure());
        }
        if (serialLane.state() != SerialLane.State.RUNNING) {
            return new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    operation + " could not be queued because the TimingNode Data Lane is not running",
                    rejection);
        }
        return new OperationException(
                OperationException.Reason.BUSY,
                operation + " could not be queued because the TimingNode Data Lane is full",
                rejection);
    }
}
