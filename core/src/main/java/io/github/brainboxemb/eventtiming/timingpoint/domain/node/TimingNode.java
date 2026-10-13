package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeQuery.ReadConsistency;

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
    private final TimingNodeMetrics metrics;
    private final Event<Status> statusChangedEvent = new Event<>();
    private final Event<TimingData> timingDataCommittedEvent = new Event<>();
    private final UpstreamMessagePort upstreamMessagePort;

    /** Latest activation-complete immutable Status visible to CURRENT queries. */
    private volatile Status publishedStatus;

    /**
     * Test-only convenience construction for tests in the TimingNode package.
     *
     * <p>Production runtime composition must use the public constructor that
     * receives centrally constructed execution lanes. This seam uses direct
     * execution and therefore creates no physical worker thread.</p>
     */
    TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                EventData.empty());
    }

    /**
     * Test-only convenience construction with explicit EventData.
     *
     * <p>This keeps tests on the same EventData/TeamAssignments resolution path
     * as production without creating physical worker threads.</p>
     */
    TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            EventData eventData) {
        this(
                new TimingNodeLogic(
                        timingNodeId,
                        timingDataPersistence,
                        timingDataFactory,
                        timeSource,
                        eventData,
                        SystemMonotonicClock.INSTANCE,
                        new TimingNodeMetrics()),
                new SerialExecutor(
                        32,
                        "test-node-" + requireId(timingNodeId).value(),
                        Runnable::run),
                DEFAULT_OPERATION_TIMEOUT_MILLIS,
                SystemMonotonicClock.INSTANCE,
                null,
                eventData);
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
            EventData eventData,
            SerialExecutor serialExecutor,
            SerialScheduledExecutor tagProcessorExecutor) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                tagProcessingConfiguration,
                eventData,
                serialExecutor,
                tagProcessorExecutor,
                SystemMonotonicClock.INSTANCE,
                new TimingNodeMetrics(),
                new TagProcessingMetrics());
    }

    public TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            ReadOnlyConfiguration<TagProcessingPolicy> tagProcessingConfiguration,
            EventData eventData,
            SerialExecutor serialExecutor,
            SerialScheduledExecutor tagProcessorExecutor,
            MonotonicClock monotonicClock) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                tagProcessingConfiguration,
                eventData,
                serialExecutor,
                tagProcessorExecutor,
                monotonicClock,
                new TimingNodeMetrics(),
                new TagProcessingMetrics());
    }

    /**
     * Engineering-composition constructor that retains the component-owned
     * metric handles used by RuntimeMeasurementReader.
     */
    public TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            ReadOnlyConfiguration<TagProcessingPolicy> tagProcessingConfiguration,
            EventData eventData,
            SerialExecutor serialExecutor,
            SerialScheduledExecutor tagProcessorExecutor,
            MonotonicClock monotonicClock,
            TimingNodeMetrics timingNodeMetrics,
            TagProcessingMetrics tagProcessingMetrics) {
        if (serialExecutor == null) {
            throw new IllegalArgumentException("serialExecutor must not be null");
        }
        if (tagProcessingConfiguration == null) {
            throw new IllegalArgumentException(
                    "tagProcessingConfiguration must not be null");
        }
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        if (tagProcessorExecutor == null) {
            throw new IllegalArgumentException(
                    "tagProcessorExecutor must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        if (timingNodeMetrics == null) {
            throw new IllegalArgumentException("timingNodeMetrics must not be null");
        }
        if (tagProcessingMetrics == null) {
            throw new IllegalArgumentException("tagProcessingMetrics must not be null");
        }

        this.logic = new TimingNodeLogic(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                eventData,
                monotonicClock,
                timingNodeMetrics);
        this.serialExecutor = serialExecutor;
        this.operationTimeoutMillis = DEFAULT_OPERATION_TIMEOUT_MILLIS;
        this.monotonicClock = monotonicClock;
        this.metrics = timingNodeMetrics;

        /*
         * TagProcessor is a child of this TimingNode aggregate. Runtime chooses
         * the executor and event-data/policy; the node creates and owns
         * the processing component itself.
         */
        this.tagProcessor = new TagProcessor(
                this,
                eventData,
                tagProcessingConfiguration,
                monotonicClock,
                tagProcessingMetrics,
                tagProcessorExecutor);

        /*
         * Child wiring is part of TimingNode construction. TagProcessor
         * activation/deactivation changes behaviour, not the event graph.
         */
        tagProcessingConfiguration.changes()
                .subscribe(
                        tagProcessor::onPolicyConfigurationChanged);

        this.upstreamMessagePort =
                new UpstreamMessagePort(
                        this,
                        eventData,
                        logic.teamAssignments(),
                        logic.stageStartTimes(),
                        logic.uniqueTeamRegistrations());
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
                null,
                EventData.empty());
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
                null,
                EventData.empty());
    }

    private TimingNode(
            TimingNodeLogic logic,
            SerialExecutor serialExecutor,
            long operationTimeoutMillis,
            MonotonicClock monotonicClock,
            TagProcessor tagProcessor,
            EventData eventData) {
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
        if (eventData == null) {
            throw new IllegalArgumentException("eventData must not be null");
        }
        this.logic = logic;
        this.serialExecutor = serialExecutor;
        this.tagProcessor = tagProcessor;
        this.operationTimeoutMillis = operationTimeoutMillis;
        this.monotonicClock = monotonicClock;
        this.metrics = logic.metrics();
        this.upstreamMessagePort =
                new UpstreamMessagePort(
                        this,
                        eventData,
                        logic.teamAssignments(),
                        logic.stageStartTimes());
    }

    public NodeId timingNodeId() {
        return logic.timingNodeId();
    }

    /**
     * Returns the TimingNode-owned semantic upstream port.
     *
     * <p>Application routing uses this port so upstream connectors never call
     * internal TimingNode logic directly.</p>
     */
    public UpstreamMessagePort upstreamMessagePort() {
        return upstreamMessagePort;
    }

    /**
     * Activates this component's execution context and schedules initial recovery.
     *
     * <p>Activation does not perform persistence I/O and does not wait for
     * recovery. The TimingNode serial lane is started first and recovery is
     * admitted as its first node task. Mutable node state, LogBook rebuild and
     * recovery error handling therefore stay on the TimingNode's own logical
     * lane. CURRENT status remains unavailable until that task publishes the
     * initial immutable Status snapshot.</p>
     */
    public void activate() {
        SerialExecutor.State executorState = serialExecutor.state();
        if (executorState != SerialExecutor.State.NEW && executorState != SerialExecutor.State.STOPPED) {
            throw new IllegalStateException("TimingNode cannot activate with executor state=" + executorState);
        }

        publishedStatus = null;
        serialExecutor.start();

        SerialExecutor.AdmissionResult initialization =
                serialExecutor.offer(this::initializeOnNodeLane);
        if (initialization != SerialExecutor.AdmissionResult.ACCEPTED) {
            serialExecutor.close();
            throw new IllegalStateException(
                    "TimingNode initial recovery could not be admitted: " + initialization);
        }

        /*
         * The node lane exists before TagProcessor activation so every accepted
         * child handoff has a live downstream target. Initial recovery was
         * already admitted first, so later node work remains ordered behind it.
         */
        if (tagProcessor != null) {
            try {
                tagProcessor.activate();
            } catch (RuntimeException ex) {
                serialExecutor.close();
                throw ex;
            }
        }
    }

    /**
     * First ordered TimingNode task after activation.
     *
     * <p>Persistence loading may block the shared physical TimingNode worker,
     * but ownership remains node-local: all recovered mutable state is written
     * while this TimingNode lane is current. Runtime may deliberately back
     * multiple logical node lanes with one physical worker.</p>
     */
    private void initializeOnNodeLane() {
        LOG.info(
                "TimingNode {} TimingData recovery started",
                timingNodeId().value());

        final TimingDataPersistence.LoadResult recovery;
        try {
            recovery = logic.recoverTimingData();
        } catch (TimingDataPersistence.PersistenceException | RuntimeException ex) {
            logic.markTimingDataRecoveryFailed(ex);
            LOG.error(
                    "TimingData recovery failed for {}; TimingNode remains available in ERROR state",
                    timingNodeId().value(),
                    ex);
            publishRecoveredStatus();
            return;
        }

        LOG.info(
                "TimingNode {} TimingData recovery completed: records={}, tailRepaired={}",
                timingNodeId().value(),
                recovery.records().size(),
                recovery.repairedIncompleteTail());
        if (recovery.repairedIncompleteTail()) {
            LOG.warn(
                    "TimingNode {} TimingData recovery repaired an incomplete final record tail",
                    timingNodeId().value());
        }

        publishRecoveredStatus();
    }

    private void publishRecoveredStatus() {
        Status initialStatus = logic.status();
        publishCurrentStatus(initialStatus);
        publishInitialStatus(initialStatus);
    }

    public void deactivate() {
        RuntimeException firstFailure = null;

        /*
         * Stop/drain tag ingress before closing the TimingNode lane. This lets
         * already accepted TagProcessor input complete its non-blocking
         * TimingNode.offer(...) handoff while that target still exists.
         */
        if (tagProcessor != null) {
            try {
                tagProcessor.deactivate();
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

        publishedStatus = null;

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

    /**
     * Returns the subscription-only stream of authoritative Status publications.
     *
     * <p>The first event follows initial recovery and makes CURRENT status
     * available. Later events represent effective status changes.</p>
     */
    public EventSource<Status> statusChangedEvent() {
        return statusChangedEvent;
    }

    /** Returns the subscription-only stream of newly committed TimingData. */
    public EventSource<TimingData> timingDataCommittedEvent() {
        return timingDataCommittedEvent;
    }

    private <R> R applyCommand(TimingNodeCommand<R> command) throws Exception {
        Status before = logic.status();
        int committedBefore = logic.committedTimingDataCount();

        R result = command.apply(logic);

        Status after = logic.status();
        publishCurrentStatus(after);

        int committedAfter = logic.committedTimingDataCount();
        if (committedAfter > committedBefore) {
            publishTimingDataCommitted(
                    logic.latestCommittedTimingData());
        }

        publishStatusChanged(before, after);
        return command.complete(this, result);
    }

    /**
     * Publishes first status availability after the activation recovery task.
     *
     * <p>This uses the existing status event so externally composed current-state
     * consumers can wake and reread CURRENT state. The event is not recovery
     * work itself and its payload is not a replacement for the current snapshot.</p>
     */
    private void publishInitialStatus(Status status) {
        LOG.info(
                "TimingNode {} initial status available: state {}, location {}, problems {}",
                timingNodeId().value(),
                status.state(),
                status.hasLocation() ? status.locationId() : "-",
                status.problems().size());

        Event.DeliveryReport delivery = statusChangedEvent.emit(status);
        if (!delivery.successful()) {
            LOG.warn(
                    "TimingNode initial status published but "
                            + delivery.failureCount()
                            + " status listener(s) failed for "
                            + timingNodeId().value(),
                    delivery.failures().get(0));
        }
    }

    private void publishStatusChanged(Status before, Status after) {
        if (sameStatus(before, after)) {
            return;
        }

        LOG.info(
                "TimingNode {} status changed: state {} -> {}, location {} -> {}, problems {} -> {}",
                timingNodeId().value(),
                before.state(),
                after.state(),
                before.hasLocation() ? before.locationId() : "-",
                after.hasLocation() ? after.locationId() : "-",
                before.problems().size(),
                after.problems().size());

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
     * Executes an ORDERED typed read after work already accepted by this node.
     *
     * <p>This one-argument form preserves the original query semantics.</p>
     */
    public <R> R query(TimingNodeQuery<R> query) {
        return query(query, ReadConsistency.ORDERED);
    }

    /**
     * Executes one typed read with the requested consistency.
     *
     * <p>CURRENT reads use only a safely published immutable read model and never
     * enter the serial lane. ORDERED reads execute on the node lane after work
     * accepted earlier.</p>
     */
    public <R> R query(
            TimingNodeQuery<R> query,
            ReadConsistency consistency) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (consistency == null) {
            throw new IllegalArgumentException("consistency must not be null");
        }

        switch (consistency) {
            case CURRENT:
                return query.readCurrent(this);
            case ORDERED:
                return runSerialized(
                        () -> query.readOrdered(logic),
                        query.name());
            default:
                throw new IllegalStateException(
                        "Unsupported read consistency " + consistency);
        }
    }

    /**
     * Returns the activation-complete immutable Status used by CURRENT status queries.
     */
    Status publishedStatus() {
        SerialExecutor.State executorState = serialExecutor.state();
        if (executorState == SerialExecutor.State.FAILED) {
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    "status CURRENT result is unavailable because the TimingNode serial executor failed",
                    serialExecutor.failure());
        }
        if (executorState != SerialExecutor.State.RUNNING) {
            throw new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    "status CURRENT result is unavailable because the TimingNode is not active and ready");
        }

        Status status = publishedStatus;
        if (status == null) {
            throw new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    "status CURRENT result is unavailable because TimingNode activation is not complete");
        }
        return status;
    }

    private void publishCurrentStatus(Status status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        publishedStatus = status;
    }

    private static boolean sameStatus(Status left, Status right) {
        if (!left.timingNodeId().equals(right.timingNodeId())) {
            return false;
        }
        if (left.state() != right.state()) {
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

    private void publishTimingDataCommitted(TimingData data) {
        long eventStartedNanos = monotonicClock.nowNanos();
        Event.DeliveryReport delivery = timingDataCommittedEvent.emit(data);
        metrics.recordEventDelivery(
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
    }

    /**
     * Executes node-owned helper work on the same serial lane as commands,
     * LogBook access and state mutation.
     *
     * <p>UpstreamMessagePort uses this seam so upstream processing cannot race
     * with registration commits or other TimingNode state changes.</p>
     */
    <R> R runNodeTask(
            Callable<R> task,
            String operation) {
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }
        return runSerialized(
                task,
                operation);
    }

    private <R> R runSerialized(Callable<R> work, String operation) {
        if (serialExecutor.isExecutingOnCurrentThread()) {
            throw new IllegalStateException(
                    "Reentrant ORDERED TimingNode operation "
                            + operation
                            + " cannot wait for the same serial lane");
        }

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
