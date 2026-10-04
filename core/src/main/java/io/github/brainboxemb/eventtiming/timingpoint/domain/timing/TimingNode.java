package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialWorker;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public component boundary for one logical timing node.
 *
 * <p>The node serializes external operations and delegates its mutable domain
 * behaviour to {@link TimingNodeLogic}. Higher layers use this component rather
 * than the internal logic object directly.</p>
 *
 * <p>Result-bearing callers use {@link #invoke(TimingNodeCommand)} and typed
 * reads use {@link #query(TimingNodeQuery)}. Producer/callback paths that must
 * not wait for the processed result use {@link #submit(TimingNodeCommand)} and
 * receive only immediate bounded-queue admission. The standard
 * {@link TimingNodeCommands} and {@link TimingNodeQueries} keep this boundary
 * compact without duplicating every operation implemented by TimingNodeLogic.</p>
 */
public final class TimingNode {
    private static final Logger LOG = LoggerFactory.getLogger(TimingNode.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 32;
    private static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 2000L;

    private final TimingNodeLogic logic;
    private final SerialWorker serialWorker;
    private final long operationTimeoutMillis;
    private final Event<Status> statusChangedEvent = new Event<>();
    private final Event<TimingData> timingDataCommittedEvent = new Event<>();


    public TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource) {
        this(
                new TimingNodeLogic(
                        timingNodeId,
                        timingDataPersistence,
                        timingDataFactory,
                        timeSource),
                workerFor(timingNodeId),
                DEFAULT_OPERATION_TIMEOUT_MILLIS);
    }

    /**
     * Package-private execution seam used only by TimingNode boundary tests.
     *
     * <p>Production composition always uses the public constructor and therefore
     * the default bounded queue and timeout. Tests use this seam only when they
     * must control worker scheduling or timeout behaviour; it is not an
     * alternative application composition.</p>
     */
    TimingNode(
            TimingNodeLogic logic,
            SerialWorker serialWorker,
            long operationTimeoutMillis) {
        if (logic == null) {
            throw new IllegalArgumentException("logic must not be null");
        }
        if (serialWorker == null) {
            throw new IllegalArgumentException("serialWorker must not be null");
        }
        if (operationTimeoutMillis < 1L) {
            throw new IllegalArgumentException("operationTimeoutMillis must be positive");
        }
        this.logic = logic;
        this.serialWorker = serialWorker;
        this.operationTimeoutMillis = operationTimeoutMillis;
    }

    public NodeId timingNodeId() {
        return logic.timingNodeId();
    }

    /**
     * Recovers committed TimingData before accepting serial operations.
     *
     * <p>Recovery does not restore the operational LocationId or OPEN state.</p>
     */
    public void start() {
        if (serialWorker.state() != SerialWorker.State.NEW) {
            throw new IllegalStateException(
                    "TimingNode can only start once; worker state=" + serialWorker.state());
        }
        try {
            logic.recoverTimingData();
        } catch (TimingDataPersistence.PersistenceException | RuntimeException ex) {
            logic.markTimingDataRecoveryFailed(ex);
            LOG.error(
                    "TimingData recovery failed for {}; TimingNode remains available in ERROR state",
                    timingNodeId().value(),
                    ex);
        }
        serialWorker.start();
    }

    public void stop() {
        serialWorker.close();
    }

    /**
     * Executes one typed state-changing command on the TimingNode serial lane.
     *
     * <p>The command contains the domain operation and its arguments. TimingNode
     * remains responsible for admission, ordering, timeout/failure mapping and
     * post-command component events.</p>
     */
    public <R> R invoke(TimingNodeCommand<R> command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        return runSerialized(
                () -> applyCommand(command),
                command.name());
    }

    /**
     * Attempts to admit one command without waiting for its processed result.
     *
     * <p>This is the producer/callback path used when the caller must return
     * promptly, for example after TagProcessor has produced accepted semantic
     * work. ACCEPTED means only that the command entered the bounded serial
     * lane; it does not mean that the later domain operation commits.</p>
     */
    public CommandAdmission submit(TimingNodeCommand<?> command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }

        SerialWorker.AdmissionResult admission =
                serialWorker.offer(() -> applySubmitted(command));
        switch (admission) {
            case ACCEPTED:
                return CommandAdmission.ACCEPTED;
            case FULL:
                return CommandAdmission.FULL;
            case NOT_RUNNING:
                return CommandAdmission.NOT_RUNNING;
            default:
                throw new IllegalStateException(
                        "Unsupported admission result " + admission);
        }
    }

    private <R> void applySubmitted(TimingNodeCommand<R> command) {
        try {
            applyCommand(command);
        } catch (Exception ex) {
            LOG.warn(
                    "Submitted TimingNode command {} failed after admission",
                    command.name(),
                    ex);
        }
    }

    /** Returns the subscription-only stream of authoritative status changes. */
    public EventSource<Status> statusChangedEvent() {
        return statusChangedEvent;
    }

    /** Returns the subscription-only stream of newly committed TimingData. */
    public EventSource<TimingData> timingDataCommittedEvent() {
        return timingDataCommittedEvent;
    }

    private <R> R applyCommand(TimingNodeCommand<R> command) throws Exception {
        Status before = logic.status();
        R result = command.apply(logic);
        Status after = logic.status();
        publishStatusChanged(before, after);
        return command.complete(this, result);
    }

    private void publishStatusChanged(Status before, Status after) {
        if (sameStatus(before, after)) {
            return;
        }

        Event.DeliveryReport delivery = statusChangedEvent.emit(after);
        if (!delivery.successful()) {
            LOG.warn(
                    "TimingNode status changed but "
                            + delivery.failureCount()
                            + " status listener(s) failed for "
                            + timingNodeId().value(),
                    delivery.failures().get(0));
        }
    }


    /**
     * Executes a typed read against the same serial lane as state-changing commands.
     *
     * <p>Queries carry the read operation instead of requiring a forwarding method
     * on TimingNode for every value exposed by TimingNodeLogic. This keeps reads
     * ordered with commands while the visible TimingNode API stays compact.</p>
     */
    public <R> R query(TimingNodeQuery<R> query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        return runSerialized(() -> query.read(logic), query.name());
    }

    private static boolean sameStatus(Status left, Status right) {
        if (!left.timingNodeId().equals(right.timingNodeId())) {
            return false;
        }
        if (left.lifecycle() != right.lifecycle()) {
            return false;
        }
        if (left.timingDataTailRecovered() != right.timingDataTailRecovered()) {
            return false;
        }
        if (!left.problems().equals(right.problems())) {
            return false;
        }
        if (!left.hasLocation()) {
            return !right.hasLocation();
        }
        return right.hasLocation()
                && left.locationId().equals(right.locationId());
    }

    RegistrationResult publishCommitted(RegistrationResult result) {
        if (!result.committed()) {
            return result;
        }

        TimingData data = result.timingData();
        Event.DeliveryReport delivery = timingDataCommittedEvent.emit(data);
        if (!delivery.successful()) {
            LOG.warn(
                    "TimingData committed but "
                            + delivery.failureCount()
                            + " timingDataCommittedEvent listener(s) failed for "
                            + timingNodeId().value()
                            + ":"
                            + data.sequenceNumber(),
                    delivery.failures().get(0));
        }
        return result;
    }

    private <R> R runSerialized(Callable<R> work, String operation) {
        SerialWorker.SubmitResult<R> submitResult = serialWorker.submit(work);
        switch (submitResult.admission()) {
            case FULL:
                throw new OperationException(
                        OperationException.Reason.BUSY,
                        operation + " could not be admitted because the TimingNode queue is full");
            case NOT_RUNNING:
                if (serialWorker.state() == SerialWorker.State.FAILED) {
                    throw new OperationException(
                            OperationException.Reason.FAILED,
                            operation + " could not run because the TimingNode worker failed",
                            serialWorker.failure());
                }
                throw new OperationException(
                        OperationException.Reason.UNAVAILABLE,
                        operation + " could not be admitted because the TimingNode is not running");
            case ACCEPTED:
                return await(submitResult.futureResult(), operation);
            default:
                throw new IllegalStateException(
                        "Unsupported submission result " + submitResult.admission());
        }
    }

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
                    operation + " was cancelled because the TimingNode worker failed",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            String detail = cause == null ? null : cause.getMessage();
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    operation + " failed while executing on the TimingNode"
                            + (detail == null || detail.trim().isEmpty()
                                    ? ""
                                    : ": " + detail.trim()),
                    cause);
        }
    }

    private static SerialWorker workerFor(NodeId timingNodeId) {
        NodeId id = requireId(timingNodeId);
        return new SerialWorker(
                DEFAULT_QUEUE_CAPACITY,
                "tp-dml-node-" + id.value());
    }

    private static NodeId requireId(NodeId timingNodeId) {
        if (timingNodeId == null) {
            throw new IllegalArgumentException("timingNodeId must not be null");
        }
        return timingNodeId;
    }

}
