package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOG = LoggerFactory.getLogger(LogBookDataLane.class);

    private final NodeId nodeId;
    private final SerialLane serialLane;
    private final long commitTimeoutMillis;
    private final long orderedQueryTimeoutMillis;

    /** Creates the LogBook Data Lane adapter around one externally composed SerialLane. */
    LogBookDataLane(
            NodeId nodeId,
            SerialLane serialLane,
            long commitTimeoutMillis,
            long orderedQueryTimeoutMillis) {

        checkArgument(nodeId != null, "nodeId must not be null");
        checkArgument(serialLane != null, "serialLane must not be null");
        checkArgument(commitTimeoutMillis > 0L, "commitTimeoutMillis must be positive");
        checkArgument(
                orderedQueryTimeoutMillis > 0L,
                "orderedQueryTimeoutMillis must be positive");
        this.nodeId = nodeId;
        this.serialLane = serialLane;
        this.commitTimeoutMillis = commitTimeoutMillis;
        this.orderedQueryTimeoutMillis = orderedQueryTimeoutMillis;
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

    /** Submits one result-bearing commit command and waits for its ordered result. */
    <R> R submitCommandAndWait(Callable<R> work, String operation) {

        return submitAndWait(
                work,
                operation,
                "command",
                commitTimeoutMillis);
    }

    /** Submits one result-bearing ORDERED query and waits for its ordered result. */
    <R> R submitQueryAndWait(Callable<R> work, String operation) {

        return submitAndWait(
                work,
                operation,
                "query",
                orderedQueryTimeoutMillis);
    }

    /** Submits one result-bearing operation using the timeout for that operation family. */
    private <R> R submitAndWait(
            Callable<R> work,
            String operation,
            String operationType,
            long timeoutMillis) {

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
            throw rejectedOperation(operation, operationType, ex);
        }
        return await(future, operation, operationType, timeoutMillis);
    }

    /** Converts Future completion into the stable LogBook operation exception contract. */
    private <R> R await(
            Future<R> future,
            String operation,
            String operationType,
            long timeoutMillis) {

        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            LOG.error(
                    "TimingNode {} LogBook {} {} timed out after {} ms; final outcome is unknown",
                    nodeId.value(),
                    operationType,
                    operation,
                    timeoutMillis,
                    ex);
            throw new OperationException(
                    OperationException.Reason.TIMEOUT,
                    operation + " timed out after " + timeoutMillis
                            + " ms; final TimingNode outcome is unknown",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.warn(
                    "TimingNode {} LogBook {} {} was interrupted while waiting for its result",
                    nodeId.value(),
                    operationType,
                    operation,
                    ex);
            throw new OperationException(
                    OperationException.Reason.INTERRUPTED,
                    operation + " was interrupted while waiting for the TimingNode result",
                    ex);
        } catch (CancellationException ex) {
            LOG.error(
                    "TimingNode {} LogBook {} {} was cancelled because the Data Lane failed",
                    nodeId.value(),
                    operationType,
                    operation,
                    ex);
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " was cancelled because the TimingNode Data Lane failed",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            String detail = cause == null ? null : cause.getMessage();
            LOG.error(
                    "TimingNode {} LogBook {} {} failed during Data Lane execution",
                    nodeId.value(),
                    operationType,
                    operation,
                    cause == null ? ex : cause);
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
            String operation,
            String operationType,
            RejectedExecutionException rejection) {

        if (serialLane.state() == SerialLane.State.FAILED) {
            Throwable cause = serialLane.failure() == null ? rejection : serialLane.failure();
            LOG.error(
                    "TimingNode {} LogBook {} {} rejected because the Data Lane failed",
                    nodeId.value(),
                    operationType,
                    operation,
                    cause);
            return new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " could not run because the TimingNode Data Lane failed",
                    cause);
        }
        if (serialLane.state() != SerialLane.State.RUNNING) {
            LOG.warn(
                    "TimingNode {} LogBook {} {} rejected because the Data Lane is unavailable",
                    nodeId.value(),
                    operationType,
                    operation,
                    rejection);
            return new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    operation + " could not be queued because the TimingNode Data Lane is not running",
                    rejection);
        }

        LOG.warn(
                "TimingNode {} LogBook {} {} rejected because the Data Lane is full",
                nodeId.value(),
                operationType,
                operation,
                rejection);
        return new OperationException(
                OperationException.Reason.BUSY,
                operation + " could not be queued because the TimingNode Data Lane is full",
                rejection);
    }
}
