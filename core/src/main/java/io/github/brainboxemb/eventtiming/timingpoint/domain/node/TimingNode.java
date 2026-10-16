package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommand;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookRecoveryStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.ErrorCode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.ErrorStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.NodeStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;

/**
 * Coordinates one timing node.
 *
 * <p>It owns the node's OPEN/CLOSED operation state and current LocationId and
 * coordinates its LogBook, NodeService and TagProcessor. That operation state is
 * transient runtime state: after a process restart, successful LogBook recovery
 * starts the node CLOSED without a LocationId. Persisted NODE_OPEN/NODE_CLOSE
 * history is deliberately not replayed here to reconstruct operation state; a
 * future persistent/reconstructed state model is a separate architecture choice.</p>
 */
public final class TimingNode {
    private static final Logger LOG = LoggerFactory.getLogger(TimingNode.class);
    private final NodeId timingNodeId;
    private final AntennaId antennaId;
    private final TimeSource timeSource;
    private final LogBookAccessor logBook;
    private final TagProcessor tagProcessor;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();
    private final Event<TimingData> timingDataCommitted = new Event<TimingData>();
    private final Event<NodeStatus> statusChanged = new Event<NodeStatus>();
    private final Object operationLock = new Object();
    private final Object stateLock = new Object();

    /** Current node status; null until LogBook recovery completes. */
    private volatile NodeStatus currentStatus;
    private boolean registrationsEnabled;

    /** Antenna observation input; internally consumed by the TagProcessor. */
    public static final EventInput<TimingNode, TagObservation> TAG_OBSERVATIONS =
            EventInput.of(
                    (node, source) -> source.subscribe(node.tagProcessor::onTagObserved));

    /** LogBook startup-recovery input used to initialize the node's current status. */
    private static final EventInput<TimingNode, LogBookRecoveryStatus> LOGBOOK_RECOVERY_STATUS =
            EventInput.of(
                    (node, source) -> source.subscribe(node::onLogBookRecoveryCompleted));

    /** Committed LogBook data input used to maintain transient runtime operation state. */
    private static final EventInput<TimingNode, TimingData> LOGBOOK_TIMING_DATA_COMMITTED =
            EventInput.of(
                    (node, source) -> source.subscribe(node::onTimingDataCommitted));

    /** Selected registration input from the TagProcessor lane. */
    private static final EventInput<TimingNode, TagProcessor.NewRegistration> NEW_REGISTRATION =
            EventInput.of(
                    (node, source) -> source.subscribe(node::onNewRegistration));

    /**
     * Committed TimingData source at the TimingNode boundary.
     *
     * <p>For NODE_OPEN/NODE_CLOSE the current operation state is already updated
     * when this event is delivered. STATUS_CHANGED follows afterwards.</p>
     */
    public final EventSource<TimingData> TIMING_DATA_COMMITTED = timingDataCommitted;

    /**
     * Status-change source for this TimingNode.
     *
     * <p>Listeners are invoked synchronously on the thread that publishes the change
     * and must return quickly.</p>
     */
    public final EventSource<NodeStatus> STATUS_CHANGED = statusChanged;

    /** Creates one TimingNode with its clock and child components. */
    public TimingNode(
            AntennaId antennaId,
            TimeSource timeSource,
            LogBook logBook,
            NodeService nodeService,
            TagProcessor tagProcessor) {

        checkArgument(antennaId != null, "antennaId must not be null");
        checkArgument(timeSource != null, "timeSource must not be null");
        checkArgument(logBook != null, "logBook must not be null");
        checkArgument(nodeService != null, "nodeService must not be null");
        checkArgument(tagProcessor != null, "tagProcessor must not be null");

        this.timingNodeId = logBook.timingNodeId();
        this.antennaId = antennaId;
        this.timeSource = timeSource;
        this.logBook = logBook;
        this.tagProcessor = tagProcessor;
        lifecycle.register(nodeService, NodeService::activate, NodeService::deactivate);
        lifecycle.register(logBook, LogBook::activate, LogBook::deactivate);
        lifecycle.register(tagProcessor, TagProcessor::activate, TagProcessor::deactivate);

        /*
         * LogBook publishes the durable commit fact once. TimingNode applies any
         * operation-state effect and republishes the node-level committed-data fact
         * before STATUS_CHANGED, keeping state/event ordering at the state owner.
         */
        LOGBOOK_TIMING_DATA_COMMITTED.bind(this, logBook.NEW_DATA_ENTRY);
        NodeService.INITIAL_TIMING_DATA.bind(nodeService, logBook.INITIAL_DATA_LOADED);
        NodeService.COMMITTED_TIMING_DATA.bind(nodeService, TIMING_DATA_COMMITTED);
        LOGBOOK_RECOVERY_STATUS.bind(this, logBook.RECOVERY_STATUS_CHANGED);

        // Listener runs synchronously on the TagProcessor lane; it must not block.
        NEW_REGISTRATION.bind(this, tagProcessor.NEW_REGISTRATION);
    }

    /** Returns this node's ID. */
    public NodeId timingNodeId() {

        return timingNodeId;
    }

    /** Returns the antenna assigned to this node. */
    public AntennaId antennaId() {

        return antennaId;
    }

    /** Returns the TimeSource used by this node. This lookup does not block. */
    public TimeSource timeSource() {

        return timeSource;
    }

    /**
     * Returns the current node status without waiting for queued Data Lane work.
     *
     * <p>Before startup recovery completes, this may report the status as unavailable.</p>
     */
    public NodeStatus status() {

        NodeStatus status = currentStatus;
        if (status != null) {
            return status;
        }

        /*
         * Before the recovery event reaches this component, preserve the
         * LogBook availability/failure semantics for a direct status query.
         */
        return statusFromRecovery(
                logBook.process(
                        LogBookQueries.recoveryStatus(),
                        ReadConsistency.CURRENT));
    }

    /**
     * Opens this node at the requested location.
     *
     * <p>This call blocks until NODE_OPEN is committed to the LogBook or the operation fails.</p>
     */
    public OpenResult open(LocationId locationId) {

        checkArgument(locationId != null, "locationId must not be null");
        synchronized (operationLock) {
            Status status = requireOperationalStatus();
            if (status.state() == State.OPEN) {
                return OpenResult.ALREADY_OPEN;
            }

            logBook.process(LogBookCommands.commitNodeOpen(locationId));
            return OpenResult.OPENED;
        }
    }

    /**
     * Closes this node and retains its last location.
     *
     * <p>This call blocks until NODE_CLOSE is committed to the LogBook or the operation fails.</p>
     */
    public CloseResult close() {

        synchronized (operationLock) {
            Status status = requireOperationalStatus();
            if (status.state() == State.CLOSED) {
                return CloseResult.ALREADY_CLOSED;
            }

            disableRegistrations();
            try {
                logBook.process(LogBookCommands.commitNodeClose(status.locationId()));
            } catch (OperationException ex) {
                if (ex.reason() != OperationException.Reason.TIMEOUT) {
                    enableRegistrationsFor(status);
                }
                throw ex;
            } catch (RuntimeException ex) {
                enableRegistrationsFor(status);
                throw ex;
            }
            return CloseResult.CLOSED;
        }
    }

    /**
     * Adds one custom automatic registration with an explicitly supplied effective time.
     *
     * <p>This call blocks until the LogBook Data Lane has committed the record,
     * rejected it because the node is not OPEN, or the operation fails. Custom
     * registrations always use {@code TagSource.API}; {@code registrationTimeSource}
     * records whether the supplied time came from the caller or this node's TimeSource.</p>
     */
    public RegistrationResult addCustomAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingData.AutomaticTimeSource registrationTimeSource) {

        checkArgument(
                registrationTimeSource == TimingData.AutomaticTimeSource.API
                        || registrationTimeSource == TimingData.AutomaticTimeSource.NODE,
                "custom automatic registration time source must be API or NODE");

        synchronized (operationLock) {
            Status status = requireOperationalStatus();
            if (status.state() != State.OPEN) {
                return RegistrationResult.nodeNotOpen();
            }
            return logBook.process(
                    LogBookCommands.commitAutomaticRegistration(
                            status.locationId(),
                            registrationId,
                            effectiveTime,
                            TimingData.TagSource.API,
                            registrationTimeSource));
        }
    }

    /**
     * Adds one manual registration.
     *
     * <p>This call blocks until the LogBook Data Lane has committed the record,
     * rejected it because the node is not OPEN, or the operation fails.</p>
     */
    public RegistrationResult addManualRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource registrationTimeSource) {

        synchronized (operationLock) {
            Status status = requireOperationalStatus();
            if (status.state() != State.OPEN) {
                return RegistrationResult.nodeNotOpen();
            }
            return logBook.process(
                    LogBookCommands.commitManualRegistration(
                            status.locationId(),
                            registrationId,
                            effectiveTime,
                            registrationTimeSource));
        }
    }

    /**
     * Returns the node's LogBook command/query boundary.
     *
     * <p>{@code process(...)} waits for an ordered result; {@code execute(...)}
     * queues fire-and-forget work and returns after queueing.</p>
     */
    public LogBookAccessor logBook() {

        return logBook;
    }

    /**
     * Starts the node's child components.
     *
     * <p>The LogBook queues persisted-data recovery during activation; this call
     * returns without waiting for that recovery to finish.</p>
     */
    public void activate() {

        lifecycle.activateAll();
    }

    /** Deactivates all owned child components in reverse lifecycle order. */
    public void deactivate() {

        lifecycle.deactivateAll();
        synchronized (stateLock) {
            currentStatus = null;
            registrationsEnabled = false;
        }
    }

    /**
     * Maintains transient operation state from successfully committed TimingData.
     *
     * <p>NEW_DATA_ENTRY is emitted only after the LogBook commit has completed.
     * Consuming that fact also covers an accepted open/close command that commits
     * after its waiting caller timed out. Persisted startup history is not replayed
     * through this path, so restart does not reconstruct OPEN/CLOSED or LocationId.</p>
     */
    private void onTimingDataCommitted(TimingData timingData) {

        boolean operationStateChanged = false;
        if (currentStatus instanceof Status) {
            if (timingData instanceof TimingData.NodeOpen) {
                setStatus(new Status(
                        State.OPEN,
                        timingData.locationId()));
                operationStateChanged = true;
            } else if (timingData instanceof TimingData.NodeClose) {
                setStatus(new Status(
                        State.CLOSED,
                        timingData.locationId()));
                operationStateChanged = true;
            }
        }

        /*
         * Preserve the public event order: committed TimingData is observable
         * before the resulting operation-status change.
         */
        timingDataCommitted.emit(timingData);

        if (operationStateChanged) {
            statusChanged.emit(currentStatus);
        }
    }

    /**
     * Applies LogBook readiness/failure to the initial runtime status.
     *
     * <p>Successful recovery does not reconstruct operation state from history.</p>
     */
    private void onLogBookRecoveryCompleted(LogBookRecoveryStatus recoveryStatus) {

        publishStatus(statusFromRecovery(recoveryStatus));
    }

    /** Builds the transient post-restart status: CLOSED/no location after successful recovery. */
    private NodeStatus statusFromRecovery(LogBookRecoveryStatus recoveryStatus) {

        if (recoveryStatus.failed()) {
            return new ErrorStatus(
                    ErrorCode.TIMING_DATA_LOAD_FAILED,
                    recoveryStatus.failureMessage());
        }
        return new Status(
                State.CLOSED,
                null);
    }

    /**
     * Returns operational status for a state-changing operation.
     *
     * <p>When startup recovery is still queued, the first state-changing call
     * waits for that ordered LogBook recovery result. Plain {@link #status()}
     * remains a non-waiting CURRENT read.</p>
     */
    private Status requireOperationalStatus() {

        NodeStatus nodeStatus = currentStatus;
        if (nodeStatus == null) {
            LogBookRecoveryStatus recoveryStatus =
                    logBook.process(
                            LogBookQueries.recoveryStatus(),
                            ReadConsistency.ORDERED);
            nodeStatus = currentStatus;
            if (nodeStatus == null) {
                nodeStatus = statusFromRecovery(recoveryStatus);
                publishStatus(nodeStatus);
            }
        }

        if (nodeStatus instanceof ErrorStatus) {
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    ((ErrorStatus) nodeStatus).message());
        }
        return (Status) nodeStatus;
    }

    /** Stores one new immutable node-state snapshot. */
    private void setStatus(NodeStatus status) {

        synchronized (stateLock) {
            currentStatus = status;
            registrationsEnabled = status instanceof Status
                    && ((Status) status).state() == State.OPEN;
        }
    }

    /** Stores and publishes one new immutable node-state snapshot. */
    private void publishStatus(NodeStatus status) {

        setStatus(status);
        statusChanged.emit(status);
    }

    /** Stops new antenna registrations before a close record is committed. */
    private void disableRegistrations() {

        synchronized (stateLock) {
            registrationsEnabled = false;
        }
    }

    /** Restores registration ingress when a close commit fails. */
    private void enableRegistrationsFor(Status status) {

        synchronized (stateLock) {
            registrationsEnabled = status.state() == State.OPEN;
        }
    }

    /**
     * Handles a registration event on the TagProcessor lane.
     *
     * <p>The LogBook command is queued fire-and-forget; this callback does not
     * wait for persistence or commit completion.</p>
     */
    private void onNewRegistration(TagProcessor.NewRegistration registration) {

        synchronized (stateLock) {
            NodeStatus nodeStatus = currentStatus;
            if (!registrationsEnabled || !(nodeStatus instanceof Status)) {
                return;
            }
            Status status = (Status) nodeStatus;
            if (status.state() != State.OPEN) {
                return;
            }

            LogBookCommand<?> command = LogBookCommands.commitAntennaRegistration(
                    status.locationId(),
                    registration.registrationId(),
                    registration.observedAt());

            Runnable queueRegistration = () -> logBook.execute(command);
            Consumer<RejectedExecutionException> onRejected = ex -> LOG.warn(
                    "Automatic registration {} for node {} could not be queued on the LogBook Data Lane",
                    registration.registrationId().value(),
                    timingNodeId.value(),
                    ex);

            SafeExecute.runOrHandleRejection(queueRegistration, onRejected);
        }
    }

}
