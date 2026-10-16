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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookFailure;
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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.FunctionalState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationalProblem;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationalState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;

/**
 * Coordinates one timing node.
 *
 * <p>It owns separate functional OPEN/CLOSED state, operational INIT/OK/ERROR
 * state and current LocationId, and coordinates its LogBook, NodeService and
 * TagProcessor. Functional state is transient runtime state: a new/restarted node
 * starts CLOSED without a LocationId. LogBook recovery and failures affect
 * operational state but never reconstruct or replace functional state.</p>
 */
public final class TimingNode extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(TimingNode.class);
    private final NodeId timingNodeId;
    private final AntennaId antennaId;
    private final TimeSource timeSource;
    private final LogBookAccessor logBook;
    private final TagProcessor tagProcessor;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();
    private final Event<TimingData> timingDataCommittedEvent = new Event<TimingData>();
    private final Event<FunctionalState> functionalStateChangedEvent =
            new Event<FunctionalState>();
    private final Event<OperationalState> operationalStateChangedEvent =
            new Event<OperationalState>();
    private final Object operationLock = new Object();
    private final Object stateLock = new Object();

    /** Current functional state; independent from operational health. */
    private volatile FunctionalState functionalState = FunctionalState.CLOSED;

    /** Current operational health; independent from functional OPEN/CLOSED state. */
    private volatile OperationalState operationalState = OperationalState.INIT;

    /** Current location, or null when no location is assigned. */
    private volatile LocationId locationId;

    /** Current operational problem detail, or null when none is active. */
    private volatile OperationalProblem operationalProblem;

    /** True while this TimingNode is active and may accept functional commands. */
    private volatile boolean active;

    /** Antenna observation input; internally consumed by the TagProcessor. */
    public static final EventInput<TimingNode, TagObservation> TAG_OBSERVATIONS =
            EventInput.of(
                    (node, source) -> source.subscribe(node.tagProcessor::onTagObserved));

    /** LogBook startup-recovery input used only to track readiness/failure. */
    private static final EventInput<TimingNode, LogBookRecoveryStatus> LOGBOOK_RECOVERY_STATUS =
            EventInput.of(
                    (node, source) -> source.subscribe(node::onLogBookRecoveryCompleted));

    /** Committed LogBook data input; this never drives functional state. */
    private static final EventInput<TimingNode, TimingData> LOGBOOK_TIMING_DATA_COMMITTED =
            EventInput.of(
                    (node, source) -> source.subscribe(node::onTimingDataCommitted));

    /** Failed LogBook command input used to update operational health. */
    private static final EventInput<TimingNode, LogBookFailure> LOGBOOK_COMMAND_FAILED =
            EventInput.of(
                    (node, source) -> source.subscribe(node::onLogBookCommandFailed));

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
    public final EventSource<TimingData> TIMING_DATA_COMMITTED = timingDataCommittedEvent;

    /** Functional OPEN/CLOSED changes for this TimingNode. */
    public final EventSource<FunctionalState> FUNCTIONAL_STATE_CHANGED =
            functionalStateChangedEvent;

    /** Operational INIT/OK/ERROR changes for this TimingNode. */
    public final EventSource<OperationalState> OPERATIONAL_STATE_CHANGED =
            operationalStateChangedEvent;

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
         * those facts for node-level consumers; lifecycle status is owned by
         * TimingNode open/close and does not depend on history persistence.
         */
        LOGBOOK_TIMING_DATA_COMMITTED.bind(this, logBook.NEW_DATA_ENTRY);
        LOGBOOK_COMMAND_FAILED.bind(this, logBook.COMMAND_FAILED);
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

    /** Returns the current functional OPEN/CLOSED state. */
    public FunctionalState functionalState() {

        return functionalState;
    }

    /** Returns the current operational INIT/OK/ERROR state. */
    public OperationalState operationalState() {

        return operationalState;
    }

    /** Returns the current location, or null when no location is assigned. */
    public LocationId locationId() {

        return locationId;
    }

    /** Returns the current operational problem, if one is active. */
    public Optional<OperationalProblem> operationalProblem() {

        return Optional.ofNullable(operationalProblem);
    }

    /**
     * Opens this node at the requested location.
     *
     * <p>The OPEN runtime state is applied immediately at the node's registration
     * admission boundary. The corresponding NODE_OPEN history record is submitted
     * to the LogBook without waiting for persistence.</p>
     *
     * @throws OperationException when the node is not available for opening
     */
    public OpenResult open(LocationId locationId) throws OperationException {

        checkArgument(locationId != null, "locationId must not be null");
        synchronized (operationLock) {
            requireActive();

            OpenResult result;
            if (functionalState == FunctionalState.OPEN) {
                result = OpenResult.ALREADY_OPEN;
            } else {
                applyFunctionalState(
                        FunctionalState.OPEN,
                        locationId,
                        LogBookCommands.commitNodeOpen(locationId));
                result = OpenResult.OPENED;
            }
            return result;
        }
    }

    /**
     * Closes this node and retains its last location.
     *
     * <p>The CLOSED runtime state immediately stops admission of new registrations.
     * Antenna inventory may stop asynchronously afterwards. The corresponding
     * NODE_CLOSE history record is submitted without waiting for persistence.</p>
     */
    public CloseResult close() {

        synchronized (operationLock) {
            CloseResult result;
            if (functionalState == FunctionalState.CLOSED) {
                result = CloseResult.ALREADY_CLOSED;
            } else {
                LocationId currentLocation = locationId;
                applyFunctionalState(
                        FunctionalState.CLOSED,
                        currentLocation,
                        LogBookCommands.commitNodeClose(currentLocation));
                result = CloseResult.CLOSED;
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
            RegistrationResult result;
            if (functionalState != FunctionalState.OPEN) {
                result = RegistrationResult.nodeNotOpen();
            } else {
                result = logBook.process(
                        LogBookCommands.commitAutomaticRegistration(
                                locationId,
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
            RegistrationResult result;
            if (functionalState != FunctionalState.OPEN) {
                result = RegistrationResult.nodeNotOpen();
            } else {
                result = logBook.process(
                        LogBookCommands.commitManualRegistration(
                                locationId,
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
        active = true;
    }

    /** Deactivates all owned child components in reverse lifecycle order. */
    @Override
    protected void onDeactivate() {

        active = false;
        lifecycle.deactivateAll();
        synchronized (stateLock) {
            functionalState = FunctionalState.CLOSED;
            operationalState = OperationalState.INIT;
            locationId = null;
            operationalProblem = null;
        }
    }

    /**
     * Publishes one successfully committed TimingData fact.
     *
     * <p>LogBook history is not authoritative for functional OPEN/CLOSED state.
     * {@link #open(LocationId)} and {@link #close()} change functional state
     * directly; this event reports only the separate durable history fact.</p>
     */
    private void onTimingDataCommitted(TimingData timingData) {

        timingDataCommittedEvent.emit(timingData);
    }

    /**
     * Updates operational health after initial LogBook recovery.
     *
     * <p>Recovery never changes functional OPEN/CLOSED state or LocationId.</p>
     */
    private void onLogBookRecoveryCompleted(LogBookRecoveryStatus recoveryStatus) {

        if (recoveryStatus.failed()) {
            setOperationalError(
                    new OperationalProblem(
                            ErrorCode.TIMING_DATA_LOAD_FAILED,
                            recoveryStatus.failureMessage()));
        } else {
            setOperationalHealthy();
        }
    }

    /** Marks a failed LogBook command as an operational TimingNode problem. */
    private void onLogBookCommandFailed(LogBookFailure failure) {

        setOperationalError(
                new OperationalProblem(
                        ErrorCode.TIMING_DATA_COMMIT_FAILED,
                        "LogBook " + failure.operation + " failed: " + failure.message));
    }

    /**
     * Applies one functional state and submits its history record in the same
     * registration-admission critical section.
     *
     * <p>The LogBook submission is fire-and-forget. Queue or later persistence
     * failure changes operational health but never rolls back functional state.</p>
     */
    private void applyFunctionalState(
            FunctionalState state,
            LocationId newLocationId,
            LogBookCommand<?> historyCommand) {

        final RejectedExecutionException[] rejection =
                new RejectedExecutionException[1];
        synchronized (stateLock) {
            /*
             * Publish location before functional state. A reader that observes
             * the volatile functional-state write can then also observe the
             * location belonging to that transition.
             */
            locationId = newLocationId;
            functionalState = state;

            Runnable queueHistory = () -> logBook.execute(historyCommand);
            SafeExecute.runOrHandleRejection(
                    queueHistory,
                    ex -> rejection[0] = ex);
        }

        functionalStateChangedEvent.emit(state);
        if (rejection[0] != null) {
            onLogBookQueueRejected("lifecycle history", rejection[0]);
        }
    }

    /** Records inability to queue required LogBook work as operational ERROR. */
    private void onLogBookQueueRejected(
            String operation,
            RejectedExecutionException failure) {

        LOG.error(
                "{} for node {} could not be queued on the LogBook Data Lane",
                operation,
                timingNodeId.value(),
                failure);
        setOperationalError(
                new OperationalProblem(
                        ErrorCode.LOGBOOK_UNAVAILABLE,
                        operation + " could not be queued"));
    }

    /** Changes operational state to OK and clears any current problem detail. */
    private void setOperationalHealthy() {

        boolean changed = false;
        synchronized (stateLock) {
            if (operationalState == OperationalState.INIT) {
                operationalState = OperationalState.OK;
                operationalProblem = null;
                changed = true;
            }
        }
        if (changed) {
            operationalStateChangedEvent.emit(OperationalState.OK);
        }
    }

    /** Changes operational state to ERROR without changing functional state. */
    private void setOperationalError(OperationalProblem problem) {

        boolean stateChanged;
        synchronized (stateLock) {
            /*
             * Keep the first active problem as the primary diagnostic cause.
             * Publish its detail before the volatile ERROR state.
             */
            if (operationalProblem == null) {
                operationalProblem = problem;
            }
            stateChanged = operationalState != OperationalState.ERROR;
            operationalState = OperationalState.ERROR;
        }

        if (stateChanged) {
            operationalStateChangedEvent.emit(OperationalState.ERROR);
        }
    }

    /** Rejects functional commands only while the TimingNode itself is inactive. */
    private void requireActive() {

        if (!active) {
            throw new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    "TimingNode " + timingNodeId.value() + " is not active");
        }
    }

    /**
     * Handles a registration event on the TagProcessor lane.
     *
     * <p>The LogBook command is queued fire-and-forget; this callback does not
     * wait for persistence or commit completion.</p>
     */
    private void onNewRegistration(TagProcessor.NewRegistration registration) {

        final RejectedExecutionException[] rejection =
                new RejectedExecutionException[1];
        synchronized (stateLock) {
            if (functionalState != FunctionalState.OPEN) {
                return;
            }

            LogBookCommand<?> command = LogBookCommands.commitAntennaRegistration(
                    locationId,
                    registration.registrationId(),
                    registration.observedAt());

            Runnable queueRegistration = () -> logBook.execute(command);
            SafeExecute.runOrHandleRejection(
                    queueRegistration,
                    ex -> rejection[0] = ex);
        }

        if (rejection[0] != null) {
            onLogBookQueueRejected(
                    "Automatic registration "
                            + registration.registrationId().value(),
                    rejection[0]);
        }
    }

}
