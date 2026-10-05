package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagRegistrationMapper;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.RuntimeMetrics;
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
 * not wait for the processed result use {@link #offer(TimingNodeCommand)} and
 * receive only immediate bounded-queue admission. The standard
 * {@link TimingNodeCommands} and {@link TimingNodeQueries} keep this boundary
 * compact without duplicating every operation implemented by TimingNodeLogic.</p>
 */
public final class TimingNode {
    private static final Logger LOG = LoggerFactory.getLogger(TimingNode.class);
    private static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 2000L;

    private final TimingNodeLogic logic;
    private final SerialExecutor serialExecutor;
    private final TagProcessor tagProcessor;
    private final long operationTimeoutMillis;
    private final MonotonicClock monotonicClock;
    private final Event<Status> statusChangedEvent = new Event<>();
    private final Event<TimingData> timingDataCommittedEvent = new Event<>();

    private volatile long timingDataEventDeliveries;
    private volatile long timingDataEventListenerFailures;
    private volatile long totalTimingDataEventNanos;
    private volatile long maxTimingDataEventNanos;


    /**
     * Test-only convenience construction for tests in the TimingNode package.
     *
     * <p>Production runtime composition must use the public constructor that
     * receives centrally constructed execution lanes. Keeping this seam
     * package-private prevents production code from silently creating its own
     * threads.</p>
     */
    TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource) {
        this(
                new TimingNodeLogic(
                        timingNodeId,
                        timingDataPersistence,
                        timingDataFactory,
                        timeSource,
                        SystemMonotonicClock.INSTANCE),
                new SerialExecutor(
                        32,
                        "tp-dml-node-" + requireId(timingNodeId).value()),
                DEFAULT_OPERATION_TIMEOUT_MILLIS,
                SystemMonotonicClock.INSTANCE,
                null);
    }

    /**
     * Constructs one complete production TimingNode with runtime-supplied lanes.
     *
     * <p>The runtime owns execution-resource construction and policy. TimingNode
     * owns the node-local behaviour that runs on those lanes, including creation
     * and lifecycle of its child {@link TagProcessor} component.</p>
     */
    public TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            ReadOnlyConfiguration<TagProcessingPolicy> tagProcessingConfiguration,
            TagRegistrationMapper tagRegistrationMapper,
            SerialExecutor serialExecutor,
            SerialScheduledExecutor tagProcessorExecutor) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                tagProcessingConfiguration,
                tagRegistrationMapper,
                serialExecutor,
                tagProcessorExecutor,
                SystemMonotonicClock.INSTANCE);
    }

    private TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            ReadOnlyConfiguration<TagProcessingPolicy> tagProcessingConfiguration,
            TagRegistrationMapper tagRegistrationMapper,
            SerialExecutor serialExecutor,
            SerialScheduledExecutor tagProcessorExecutor,
            MonotonicClock monotonicClock) {
        if (serialExecutor == null) {
            throw new IllegalArgumentException("serialExecutor must not be null");
        }
        if (tagProcessingConfiguration == null) {
            throw new IllegalArgumentException(
                    "tagProcessingConfiguration must not be null");
        }
        if (tagRegistrationMapper == null) {
            throw new IllegalArgumentException(
                    "tagRegistrationMapper must not be null");
        }
        if (tagProcessorExecutor == null) {
            throw new IllegalArgumentException(
                    "tagProcessorExecutor must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }

        this.logic = new TimingNodeLogic(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                monotonicClock);
        this.serialExecutor = serialExecutor;
        this.operationTimeoutMillis = DEFAULT_OPERATION_TIMEOUT_MILLIS;
        this.monotonicClock = monotonicClock;

        /*
         * TagProcessor is a child of this TimingNode aggregate. Runtime chooses
         * the executor and deployment mapping/policy; the node creates and owns
         * the processing component itself.
         */
        this.tagProcessor = new TagProcessor(
                this,
                tagRegistrationMapper,
                tagProcessingConfiguration,
                monotonicClock,
                new TagProcessingMetrics(),
                tagProcessorExecutor);
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
            SerialExecutor serialExecutor,
            long operationTimeoutMillis) {
        this(
                logic,
                serialExecutor,
                operationTimeoutMillis,
                SystemMonotonicClock.INSTANCE,
                null);
    }

    TimingNode(
            TimingNodeLogic logic,
            SerialExecutor serialExecutor,
            long operationTimeoutMillis,
            MonotonicClock monotonicClock) {
        this(
                logic,
                serialExecutor,
                operationTimeoutMillis,
                monotonicClock,
                null);
    }

    private TimingNode(
            TimingNodeLogic logic,
            SerialExecutor serialExecutor,
            long operationTimeoutMillis,
            MonotonicClock monotonicClock,
            TagProcessor tagProcessor) {
        if (logic == null) {
            throw new IllegalArgumentException("logic must not be null");
        }
        if (serialExecutor == null) {
            throw new IllegalArgumentException("serialExecutor must not be null");
        }
        if (operationTimeoutMillis < 1L) {
            throw new IllegalArgumentException("operationTimeoutMillis must be positive");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        this.logic = logic;
        this.serialExecutor = serialExecutor;
        this.tagProcessor = tagProcessor;
        this.operationTimeoutMillis = operationTimeoutMillis;
        this.monotonicClock = monotonicClock;
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
        if (serialExecutor.state() != SerialExecutor.State.NEW) {
            throw new IllegalStateException(
                    "TimingNode can only start once; executor state=" + serialExecutor.state());
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

        /*
         * The node serial lane starts before TagProcessor so every accepted tag
         * result has a running downstream handoff target. TagProcessor owns
         * its own scheduled serial lane but its lifecycle belongs to this
         * TimingNode aggregate.
         */
        serialExecutor.start();
        if (tagProcessor != null) {
            try {
                tagProcessor.start();
            } catch (RuntimeException ex) {
                serialExecutor.close();
                throw ex;
            }
        }
    }

    public void stop() {
        RuntimeException firstFailure = null;

        /*
         * Stop/drain tag ingress before closing the TimingNode lane. This lets
         * already accepted TagProcessor input complete its non-blocking
         * TimingNode.offer(...) handoff while that target still exists.
         */
        if (tagProcessor != null) {
            try {
                tagProcessor.stop();
            } catch (RuntimeException ex) {
                firstFailure = ex;
            }
        }

        try {
            serialExecutor.close();
        } catch (RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /**
     * Returns the node-local TagProcessor child for runtime I/O wiring.
     *
     * <p>Presentation and application use cases do not call this method. Runtime
     * composition uses it only to connect decoded antenna observations to the
     * TimingNode-owned processing component.</p>
     */
    public TagProcessor tagProcessor() {
        if (tagProcessor == null) {
            throw new IllegalStateException(
                    "TagProcessor is unavailable on the TimingNode test-only construction seam");
        }
        return tagProcessor;
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
     * Offers one command to this node without waiting for its processed result.
     *
     * <p>This is the fire-and-forget producer boundary used when a higher-priority
     * producer must return promptly. The method performs only immediate bounded
     * queue admission to the TimingNode serial lane. ACCEPTED means that ownership
     * of the command was handed off to that lower-priority lane; it does not mean
     * that the later domain operation ran or committed.</p>
     */
    public CommandAdmission offer(TimingNodeCommand<?> command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }

        SerialExecutor.AdmissionResult admission =
                serialExecutor.offer(() -> applySubmitted(command));
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
        long eventStartedNanos = monotonicClock.nowNanos();
        Event.DeliveryReport delivery = timingDataCommittedEvent.emit(data);
        recordTimingDataEventDelivery(
                monotonicClock.nowNanos() - eventStartedNanos,
                delivery.failureCount());
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

    /**
     * Returns an explicit pull-based engineering snapshot of runtime counters.
     *
     * <p>Calling this method may allocate the snapshot itself and query JVM
     * thread CPU state. The registration hot path retains only primitive
     * counters and monotonic timestamps.</p>
     */
    public RuntimeMetrics runtimeMetrics() {
        SerialExecutor.Metrics.Snapshot executorMetrics =
                serialExecutor.metrics().snapshot();

        return new RuntimeMetrics(
                executorMetrics.queueDepth(),
                executorMetrics.queueHighWaterMark(),
                executorMetrics.acceptedCount(),
                executorMetrics.fullCount(),
                executorMetrics.notRunningCount(),
                executorMetrics.completedCount(),
                executorMetrics.totalQueueWaitNanos(),
                executorMetrics.maxQueueWaitNanos(),
                executorMetrics.totalExecutionNanos(),
                executorMetrics.maxExecutionNanos(),
                logic.timingDataAppendAttempts(),
                logic.timingDataAppendFailures(),
                logic.timingDataCommitCount(),
                logic.totalTimingDataAppendNanos(),
                logic.maxTimingDataAppendNanos(),
                timingDataEventDeliveries,
                timingDataEventListenerFailures,
                totalTimingDataEventNanos,
                maxTimingDataEventNanos,
                executorMetrics.workerThreadCpuTimeNanos());
    }

    private void recordTimingDataEventDelivery(
            long elapsedNanos,
            int listenerFailures) {
        long safeElapsed = elapsedNanos < 0L ? 0L : elapsedNanos;
        timingDataEventDeliveries++;
        timingDataEventListenerFailures += listenerFailures;
        totalTimingDataEventNanos += safeElapsed;
        if (safeElapsed > maxTimingDataEventNanos) {
            maxTimingDataEventNanos = safeElapsed;
        }
    }

    private <R> R runSerialized(Callable<R> work, String operation) {
        SerialExecutor.SubmitResult<R> submitResult = serialExecutor.submit(work);
        switch (submitResult.admission()) {
            case FULL:
                throw new OperationException(
                        OperationException.Reason.BUSY,
                        operation + " could not be admitted because the TimingNode queue is full");
            case NOT_RUNNING:
                if (serialExecutor.state() == SerialExecutor.State.FAILED) {
                    throw new OperationException(
                            OperationException.Reason.FAILED,
                            operation + " could not run because the TimingNode serial executor failed",
                            serialExecutor.failure());
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
                    operation + " was cancelled because the TimingNode serial executor failed",
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

    private static NodeId requireId(NodeId timingNodeId) {
        if (timingNodeId == null) {
            throw new IllegalArgumentException("timingNodeId must not be null");
        }
        return timingNodeId;
    }

}
