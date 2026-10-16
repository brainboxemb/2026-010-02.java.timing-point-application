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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookRecoveryStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;

/**
 * Coordinates one timing node.
 *
 * <p>It owns the node's OPEN/CLOSED operation state and current LocationId and
 * coordinates its LogBook, NodeService and TagProcessor. That operation state is
 * transient runtime state: a new/restarted node starts CLOSED without a LocationId.
 * LogBook recovery is independent and persisted NODE_OPEN/NODE_CLOSE history is
 * deliberately not replayed to reconstruct this status. A future persistent or
 * reconstructed status model is a separate architecture choice.</p>
 */
public final class TimingNode extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(TimingNode.class);
    private final NodeId timingNodeId;
    private final AntennaId antennaId;
    private final TimeSource timeSource;
    private final LogBookAccessor logBook;
    private final TagProcessor tagProcessor;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();
    private final Event<TimingData> timingDataCommitted = new Event<TimingData>();
    private final Event<Status> statusChanged = new Event<Status>();
    private final Event<ErrorStatus> errorStatusChanged = new Event<ErrorStatus>();
    private final Object operationLock = new Object();
    private final Object stateLock = new Object();

    /** Current transient node status; independent from LogBook recovery state. */
    private volatile Status currentStatus = new Status(State.CLOSED, null);

    /** Current error status, or null when no TimingNode error is active. */
    private volatile ErrorStatus currentErrorStatus;

    private boolean registrationsEnabled;

    /** Antenna observation input; internally consumed by the TagProcessor. */
    public static final EventInput<TimingNode, TagObservation> TAG_OBSERVATIONS =
            EventInput.of(
                    (node, source) -> source.subscribe(node.tagProcessor::onTagObserved));

    /** LogBook startup-recovery input used only to track readiness/failure. */
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
     * <p>This event reports durable LogBook facts only. Runtime OPEN/CLOSED
     * status is owned and published separately by TimingNode open/close.</p>
     */
    public final EventSource<TimingData> TIMING_DATA_COMMITTED = timingDataCommitted;

    /** OPEN/CLOSED operation-status changes for this TimingNode. */
    public final EventSource<Status> STATUS_CHANGED = statusChanged;

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
        lifecycle.register(nodeService);
        lifecycle.register(logBook);
        lifecycle.register(tagProcessor);

        /*
         * LogBook publishes durable TimingData facts once. TimingNode republishes
         * those facts for node-level consumers; lifecycle status is changed only
         * by successful TimingNode open/close operations.
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

    /** Returns the current transient OPEN/CLOSED status. */
    public Status status() {

        return currentStatus;
    }

    /** Returns the current TimingNode error, if one is active. */
    public Optional<ErrorStatus> errorStatus() {

        return Optional.ofNullable(currentErrorStatus);
    }

    /**
     * Opens this node at the requested location.
     *
     * <p>This call blocks until NODE_OPEN is committed to the LogBook or the operation fails.
     * After a successful commit, TimingNode applies the OPEN runtime status and publishes
     * {@link #STATUS_CHANGED} itself.</p>
     *
     * @throws OperationException when the node operation cannot return its normal result
     */
    public OpenResult open(LocationId locationId) throws OperationException {

        checkArgument(locationId != null, "locationId must not be null");
        synchronized (operationLock) {
            Status status = currentStatus;
            OpenResult result;
            if (status.state == State.OPEN) {
                result = OpenResult.ALREADY_OPEN;
            } else {
                result = logBook.process(
                        LogBookCommands.commitNodeOpen(locationId));

                Status openedStatus =
                        new Status(State.OPEN, locationId);
                setStatus(openedStatus);
                statusChanged.emit(openedStatus);
            }
            return result;
        }
    }

    /**
     * Closes this node and retains its last location.
     *
     * <p>This call blocks until NODE_CLOSE is committed to the LogBook or the operation fails.
     * After a successful commit, TimingNode applies the CLOSED runtime status and publishes
     * {@link #STATUS_CHANGED} itself.</p>
     *
     * @throws OperationException when the node operation cannot return its normal result;
     *         timeout means the final close outcome is unknown
     */
    public CloseResult close() throws OperationException {

        synchronized (operationLock) {
            Status status = currentStatus;
            CloseResult result;
            if (status.state == State.CLOSED) {
                result = CloseResult.ALREADY_CLOSED;
            } else {
                /*
                 * Stop antenna ingress before submitting NODE_CLOSE. Without this
                 * gate, a TagProcessor callback could queue another registration
                 * behind the close command while the close caller is still waiting.
                 */
                blockAntennaRegistrationIngressForClose();

                try {
                    result = logBook.process(
                            LogBookCommands.commitNodeClose(status.locationId));

                    Status closedStatus =
                            new Status(State.CLOSED, status.locationId);
                    setStatus(closedStatus);
                    statusChanged.emit(closedStatus);
                } catch (OperationException ex) {
                    if (ex.reason() == OperationException.Reason.BUSY
                            || ex.reason() == OperationException.Reason.UNAVAILABLE) {
                        synchronized (stateLock) {
                            if (currentStatus.state == State.OPEN) {
                                registrationsEnabled = true;
                            }
                        }
                    }
                    throw ex;
                }
            }
            return result;
        }
    }

    /**
     * Adds one test automatic registration with an explicitly supplied effective time.
     *
     * <p>This call blocks until the LogBook Data Lane has committed the record,
     * rejected it because the node is not OPEN, or the operation fails. Test
     * registrations always use {@code TagSource.API}; {@code registrationTimeSource}
     * records whether the supplied time came from the caller or this node's TimeSource.</p>
     *
     * @throws OperationException when the node operation cannot return its normal result
     */
    public RegistrationResult addTestAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingData.AutomaticTimeSource registrationTimeSource)
            throws OperationException {

        checkArgument(
                registrationTimeSource == TimingData.AutomaticTimeSource.API
                        || registrationTimeSource == TimingData.AutomaticTimeSource.NODE,
                "test automatic registration time source must be API or NODE");

        synchronized (operationLock) {
            Status status = currentStatus;
            RegistrationResult result;
            if (status.state != State.OPEN) {
                result = RegistrationResult.nodeNotOpen();
            } else {
                result = logBook.process(
                        LogBookCommands.commitAutomaticRegistration(
                                status.locationId,
                                registrationId,
                                effectiveTime,
                                TimingData.TagSource.API,
                                registrationTimeSource));
            }
            return result;
        }
    }

    /**
     * Adds one manual registration.
     *
     * <p>This call blocks until the LogBook Data Lane has committed the record,
     * rejected it because the node is not OPEN, or the operation fails.</p>
     *
     * @throws OperationException when the node operation cannot return its normal result
     */
    public RegistrationResult addManualRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource registrationTimeSource)
            throws OperationException {

        synchronized (operationLock) {
            Status status = currentStatus;
            RegistrationResult result;
            if (status.state != State.OPEN) {
                result = RegistrationResult.nodeNotOpen();
            } else {
                result = logBook.process(
                        LogBookCommands.commitManualRegistration(
                                status.locationId,
                                registrationId,
                                effectiveTime,
                                registrationTimeSource));
            }
            return result;
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
    @Override
    protected void onActivate() {

        lifecycle.activateAll();
    }

    /** Deactivates all owned child components in reverse lifecycle order. */
    @Override
    protected void onDeactivate() {

        lifecycle.deactivateAll();
        synchronized (stateLock) {
            currentStatus = new Status(State.CLOSED, null);
            currentErrorStatus = null;
            registrationsEnabled = false;
        }
    }

    /**
     * Publishes one successfully committed TimingData fact.
     *
     * <p>LogBook history is not authoritative for the transient TimingNode
     * OPEN/CLOSED state. Lifecycle status changes are applied by
     * {@link #open(LocationId)} and {@link #close()} after their commit command
     * returns successfully.</p>
     */
    private void onTimingDataCommitted(TimingData timingData) {

        timingDataCommitted.emit(timingData);
    }

    /**
     * Records LogBook recovery failure separately from TimingNode status.
     *
     * <p>Recovery never changes OPEN/CLOSED or LocationId.</p>
     */
    private void onLogBookRecoveryCompleted(LogBookRecoveryStatus recoveryStatus) {

        ErrorStatus error = recoveryStatus.failed()
                ? new ErrorStatus(
                        ErrorCode.TIMING_DATA_LOAD_FAILED,
                        recoveryStatus.failureMessage())
                : null;

        currentErrorStatus = error;
        if (error != null) {
            errorStatusChanged.emit(error);
        }
    }

    /** Stores one new immutable status snapshot. */
    private void setStatus(Status status) {

        synchronized (stateLock) {
            currentStatus = status;
            registrationsEnabled = status.state == State.OPEN;
        }
    }

    /**
     * Stops new antenna registrations while a CLOSE command is being admitted/committed.
     *
     * <p>This is an ingress gate, not a third TimingNode lifecycle state. The
     * authoritative node state remains OPEN until NODE_CLOSE is committed.</p>
     */
    private void blockAntennaRegistrationIngressForClose() {

        synchronized (stateLock) {
            registrationsEnabled = false;
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
            Status status = currentStatus;
            if (!registrationsEnabled || status.state != State.OPEN) {
                return;
            }

            LogBookCommand<?> command = LogBookCommands.commitAntennaRegistration(
                    status.locationId,
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
