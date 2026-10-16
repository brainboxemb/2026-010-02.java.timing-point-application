package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.Property;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyContainer;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyKey;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.FunctionalState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;

/**
 * Coordinates one timing node.
 *
 * <p>It owns the current OPEN/CLOSED state and LocationId, and coordinates its
 * LogBook, NodeService and TagProcessor. Runtime state is transient: a new or
 * restarted node starts CLOSED without a LocationId. LogBook stores the corresponding
 * TimingData records but does not reconstruct current state from them.</p>
 */
public final class TimingNode extends LifecycleComponent {
    /** Typed identity of the functional OPEN/CLOSED property. */
    public static final PropertyKey<FunctionalState> FUNCTIONAL_STATE =
            PropertyKey.of("functionalState");

    /** Typed identity of the current location property. */
    public static final PropertyKey<LocationId> LOCATION_ID =
            PropertyKey.of("locationId");

    private final NodeId timingNodeId;
    private final AntennaId antennaId;
    private final TimeSource timeSource;
    private final LogBookAccessor logBook;
    private final TagProcessor tagProcessor;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();
    private final Event<TimingData> timingDataCommittedEvent = new Event<TimingData>();

    // Current TimingNode state.
    private final PropertyContainer properties = new PropertyContainer();
    private final Property<FunctionalState> functionalStateProp =
            properties.property(FUNCTIONAL_STATE, FunctionalState.CLOSED);
    private final Property<LocationId> locationIdProp =
            properties.property(LOCATION_ID, null);

    private final Object operationLock = new Object();
    private final Object stateLock = new Object();

    /** Antenna observation input; internally consumed by the TagProcessor. */
    public static final EventInput<TimingNode, TagObservation> TAG_OBSERVATIONS =
            EventInput.of(
                    (node, source) -> source.subscribe(node.tagProcessor::onTagObserved));

    /** Committed LogBook data input; this never drives functional state. */
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
     * <p>This event reports committed LogBook TimingData only. Runtime OPEN/CLOSED
     * status is owned and published separately by TimingNode open/close.</p>
     */
    public final EventSource<TimingData> TIMING_DATA_COMMITTED = timingDataCommittedEvent;

    /** Current properties and their change subscriptions for this TimingNode. */
    public final PropertyContainer PROPERTIES = properties;

    /** Creates one TimingNode with its clock and child components. */
    public TimingNode(
            AntennaId antennaId,
            TimeSource timeSource,
            LogBook logBook,
            NodeService nodeService,
            TagProcessor tagProcessor) {

        super(
                () -> new OperationException(
                        OperationException.Reason.UNAVAILABLE,
                        "TimingNode " + logBook.timingNodeId().value() + " is not active"));

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
         * LogBook publishes committed TimingData once. TimingNode republishes
         * those records for node-level consumers; lifecycle status is owned by
         * TimingNode open/close and does not depend on TimingData persistence.
         */
        LOGBOOK_TIMING_DATA_COMMITTED.bind(this, logBook.NEW_DATA_ENTRY);
        NodeService.INITIAL_TIMING_DATA.bind(nodeService, logBook.INITIAL_DATA_LOADED);
        NodeService.COMMITTED_TIMING_DATA.bind(nodeService, TIMING_DATA_COMMITTED);

        // Listener runs synchronously on the TagProcessor lane; it must not block.
        NEW_REGISTRATION.bind(this, tagProcessor.NEW_REGISTRATION);
    }

    /** Returns this node's ID. */
    public NodeId nodeId() {

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

        return functionalStateProp.get();
    }

    /** Returns the current location, or null when no location is assigned. */
    public LocationId locationId() {

        return locationIdProp.get();
    }

    /**
     * Opens this node at the requested location.
     *
     * <p>The OPEN runtime state is applied immediately at the node's registration
     * admission boundary. The corresponding NODE_OPEN TimingData record is submitted
     * to the LogBook without waiting for persistence.</p>
     *
     * @throws OperationException when the node is not available for opening
     */
    public OpenResult open(LocationId locationId) throws OperationException {

        checkArgument(locationId != null, "locationId must not be null");
        synchronized (operationLock) {
            failIfInactive();

            OpenResult result;
            if (functionalStateProp.get() == FunctionalState.OPEN) {
                result = OpenResult.ALREADY_OPEN;
            } else {
                synchronized (stateLock) {
                    locationIdProp.set(locationId);
                    functionalStateProp.set(FunctionalState.OPEN);
                    logBook.execute(LogBookCommands.commitNodeOpen(locationId));
                }
                properties.update();
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
     * NODE_CLOSE TimingData record is submitted without waiting for persistence.</p>
     */
    public CloseResult close() {

        synchronized (operationLock) {
            CloseResult result;
            if (functionalStateProp.get() == FunctionalState.CLOSED) {
                result = CloseResult.ALREADY_CLOSED;
            } else {
                LocationId currentLocation = locationIdProp.get();
                synchronized (stateLock) {
                    functionalStateProp.set(FunctionalState.CLOSED);
                    logBook.execute(LogBookCommands.commitNodeClose(currentLocation));
                }
                properties.update();
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
            if (functionalStateProp.get() != FunctionalState.OPEN) {
                result = RegistrationResult.nodeNotOpen();
            } else {
                result = logBook.process(
                        LogBookCommands.commitAutomaticRegistration(
                                locationIdProp.get(),
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
            if (functionalStateProp.get() != FunctionalState.OPEN) {
                result = RegistrationResult.nodeNotOpen();
            } else {
                result = logBook.process(
                        LogBookCommands.commitManualRegistration(
                                locationIdProp.get(),
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
            functionalStateProp.set(FunctionalState.CLOSED);
            locationIdProp.set(null);
            properties.sync();
        }
    }

    /**
     * Publishes one successfully committed TimingData record.
     *
     * <p>Committed LogBook TimingData is not authoritative for functional OPEN/CLOSED
     * state. {@link #open(LocationId)} and {@link #close()} change functional state
     * directly; this event only republishes the committed TimingData record.</p>
     */
    private void onTimingDataCommitted(TimingData timingData) {

        timingDataCommittedEvent.emit(timingData);
    }

    /**
     * Handles a registration event on the TagProcessor lane.
     *
     * <p>The LogBook command is queued fire-and-forget; this callback does not
     * wait for persistence or commit completion.</p>
     */
    private void onNewRegistration(TagProcessor.NewRegistration registration) {

        synchronized (stateLock) {
            if (functionalStateProp.get() != FunctionalState.OPEN) {
                return;
            }

            logBook.execute(
                    LogBookCommands.commitAntennaRegistration(
                            locationIdProp.get(),
                            registration.registrationId(),
                            registration.observedAt()));
        }
    }

}
