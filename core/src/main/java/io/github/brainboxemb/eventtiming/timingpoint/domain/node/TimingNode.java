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
 * <p>It owns the node's OPEN/CLOSED state and coordinates its LogBook,
 * NodeService and TagProcessor.</p>
 */
public final class TimingNode {
    private static final Logger LOG = LoggerFactory.getLogger(TimingNode.class);
    private final NodeId timingNodeId;
    private final AntennaId antennaId;
    private final TimeSource timeSource;
    private final LogBookAccessor logBook;
    private final TagProcessor tagProcessor;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();
    private final Event<NodeStatus> statusChangedEvent = new Event<NodeStatus>();
    private final Object operationLock = new Object();
    private final Object stateLock = new Object();

    /** Current node status; null until LogBook recovery completes. */
    private volatile NodeStatus currentStatus;
    private boolean registrationsEnabled;

    /** Antenna observation input; internally consumed by the TagProcessor. */
    public static final EventInput<TimingNode, TagObservation> TAG_OBSERVATIONS =
            EventInput.of(
                    (node, source) -> source.subscribe(node.tagProcessor::onTagObserved));

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

        nodeService.bind(NodeService.INITIAL_TIMING_DATA, logBook.initialDataLoadedEvent());
        nodeService.bind(NodeService.COMMITTED_TIMING_DATA, logBook.newDataEntryEvent());
        logBook.recoveryStatusChangedEvent().subscribe(this::onLogBookRecoveryCompleted);
        logBook.nodeLifecycleStateEvent().subscribe(this::onCommittedTimingData);
        logBook.nodeLifecycleCommittedEvent().subscribe(this::onLifecycleCommitPublished);

        // Listener runs synchronously on the TagProcessor lane; it must not block.
        tagProcessor.newRegistrationEvent().subscribe(this::onNewRegistration);
    }

    /** Binds an event source during composition. This call is direct and does not queue work. */
    public <T> void bind(EventInput<TimingNode, T> input, EventSource<T> source) {

        checkArgument(input != null, "input must not be null");
        checkArgument(source != null, "source must not be null");
        input.bind(this, source);
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
     * Returns the node-status event.
     *
     * <p>Listeners are invoked synchronously on the thread that publishes the change
     * and must return quickly.</p>
     */
    public EventSource<NodeStatus> statusChangedEvent() {

        return statusChangedEvent;
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

    /** Called synchronously on the LogBook Data Lane after NODE_OPEN or NODE_CLOSE commits. */
    private void onCommittedTimingData(TimingData timingData) {

        NodeStatus nodeStatus = currentStatus;
        if (!(nodeStatus instanceof Status)) {
            return;
        }
        Status status = (Status) nodeStatus;

        if (timingData instanceof TimingData.NodeOpen) {
            setStatus(new Status(
                    timingNodeId,
                    State.OPEN,
                    timingData.locationId()));
        } else if (timingData instanceof TimingData.NodeClose) {
            setStatus(new Status(
                    timingNodeId,
                    State.CLOSED,
                    timingData.locationId()));
        }
    }

    /** Called synchronously on the LogBook Data Lane after committed-data publication. */
    private void onLifecycleCommitPublished(TimingData timingData) {

        NodeStatus status = currentStatus;
        if (status != null) {
            statusChangedEvent.emit(status);
        }
    }

    /** Applies LogBook recovery facts to the node's initial runtime status. */
    private void onLogBookRecoveryCompleted(LogBookRecoveryStatus recoveryStatus) {

        publishStatus(statusFromRecovery(recoveryStatus));
    }

    /** Builds initial node status from LogBook recovery without reconstructing OPEN state. */
    private NodeStatus statusFromRecovery(LogBookRecoveryStatus recoveryStatus) {

        if (recoveryStatus.failed()) {
            return new ErrorStatus(
                    timingNodeId,
                    ErrorCode.TIMING_DATA_LOAD_FAILED,
                    recoveryStatus.failureMessage());
        }
        return new Status(
                timingNodeId,
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
        statusChangedEvent.emit(status);
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

            LogBookCommand<?> command = LogBookCommands.addAutomaticRegistration(
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
