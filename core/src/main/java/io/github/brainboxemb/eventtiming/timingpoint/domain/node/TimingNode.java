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

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.ErrorCode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.ErrorStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationStatus;

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
    private final Event<OperationStatus> operationStatusChanged = new Event<OperationStatus>();
    private final Event<ErrorStatus> errorStatusChanged = new Event<ErrorStatus>();
    private final Object operationLock = new Object();
    private final Object stateLock = new Object();

    /** Current operation status; null until LogBook recovery completes. */
    private volatile OperationStatus currentOperationStatus;

    /** Current error status, or null when no TimingNode error is active. */
    private volatile ErrorStatus currentErrorStatus;

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
     * when this event is delivered. OPERATION_STATUS_CHANGED follows afterwards.</p>
     */
    public final EventSource<TimingData> TIMING_DATA_COMMITTED = timingDataCommitted;

    /** OPEN/CLOSED operation-status changes for this TimingNode. */
    public final EventSource<OperationStatus> OPERATION_STATUS_CHANGED = operationStatusChanged;

    /** TimingNode error-status changes. */
    public final EventSource<ErrorStatus> ERROR_STATUS_CHANGED = errorStatusChanged;

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
         * before OPERATION_STATUS_CHANGED, keeping state/event ordering at the state owner.
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
     * Returns the current OPEN/CLOSED operation status.
     *
     * <p>The status becomes available when LogBook recovery completes. It is
     * transient runtime state and is reset across activation/restart.</p>
     */
    public OperationStatus operationStatus() {

        OperationStatus status = currentOperationStatus;
        if (status == null) {
            throw new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    "TimingNode operation status is not available");
        }
        return status;
    }

    /** Returns the current TimingNode error, if one is active. */
    public Optional<ErrorStatus> errorStatus() {

        return Optional.ofNullable(currentErrorStatus);
    }

    /**
     * Opens this node at the requested location.
     *
     * <p>This call blocks until NODE_OPEN is committed to the LogBook or the operation fails.</p>
     */
    public OpenResult open(LocationId locationId) {

        checkArgument(locationId != null, "locationId must not be null");
        synchronized (operationLock) {
            OperationStatus status = requireOperationalStatus();
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
            OperationStatus status = requireOperationalStatus();
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
            OperationStatus status = requireOperationalStatus();
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
            OperationStatus status = requireOperationalStatus();
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
            currentOperationStatus = null;
            currentErrorStatus = null;
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

        OperationStatus changedStatus = null;
        if (currentOperationStatus != null) {
            if (timingData instanceof TimingData.NodeOpen) {
                changedStatus = new OperationStatus(
                        State.OPEN,
                        timingData.locationId());
                setOperationStatus(changedStatus);
            } else if (timingData instanceof TimingData.NodeClose) {
                changedStatus = new OperationStatus(
                        State.CLOSED,
                        timingData.locationId());
                setOperationStatus(changedStatus);
            }
        }

        /*
         * Preserve the public event order: committed TimingData is observable
         * before the resulting operation-status change.
         */
        timingDataCommitted.emit(timingData);

        if (changedStatus != null) {
            operationStatusChanged.emit(changedStatus);
        }
    }

    /**
     * Applies LogBook recovery without merging error and operation status.
     *
     * <p>Successful or failed recovery makes the transient operation status
     * available as CLOSED/no-location. A failure is stored separately.</p>
     */
    private void onLogBookRecoveryCompleted(LogBookRecoveryStatus recoveryStatus) {

        publishRecoveryStatus(recoveryStatus);
    }

    /**
     * Returns operation status for a state-changing operation.
     *
     * <p>When startup recovery is still queued, the first state-changing call
     * waits for its ordered recovery result. Recovery errors are checked
     * separately from OPEN/CLOSED operation status.</p>
     */
    private OperationStatus requireOperationalStatus() {

        OperationStatus status = currentOperationStatus;
        if (status == null) {
            LogBookRecoveryStatus recoveryStatus =
                    logBook.process(
                            LogBookQueries.recoveryStatus(),
                            ReadConsistency.ORDERED);
            status = currentOperationStatus;
            if (status == null) {
                publishRecoveryStatus(recoveryStatus);
                status = currentOperationStatus;
            }
        }

        ErrorStatus error = currentErrorStatus;
        if (error != null) {
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    error.message());
        }
        return status;
    }

    /** Stores one new immutable operation-status snapshot. */
    private void setOperationStatus(OperationStatus status) {

        synchronized (stateLock) {
            currentOperationStatus = status;
            registrationsEnabled = status.state() == State.OPEN;
        }
    }

    /** Publishes the initial runtime status after LogBook recovery. */
    private void publishRecoveryStatus(LogBookRecoveryStatus recoveryStatus) {

        OperationStatus operationStatus = new OperationStatus(State.CLOSED, null);
        ErrorStatus error = recoveryStatus.failed()
                ? new ErrorStatus(
                        ErrorCode.TIMING_DATA_LOAD_FAILED,
                        recoveryStatus.failureMessage())
                : null;

        synchronized (stateLock) {
            currentOperationStatus = operationStatus;
            currentErrorStatus = error;
            registrationsEnabled = false;
        }

        if (error != null) {
            errorStatusChanged.emit(error);
        } else {
            operationStatusChanged.emit(operationStatus);
        }
    }

    /** Stops new antenna registrations before a close record is committed. */
    private void disableRegistrations() {

        synchronized (stateLock) {
            registrationsEnabled = false;
        }
    }

    /** Restores registration ingress when a close commit fails. */
    private void enableRegistrationsFor(OperationStatus status) {

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
            OperationStatus status = currentOperationStatus;
            if (!registrationsEnabled || status == null) {
                return;
            }
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
