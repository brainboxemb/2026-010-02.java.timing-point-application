package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.Property;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyContainer;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyKey;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.function.Supplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.FunctionalState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationalState;

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

    /** Typed identity of the runtime readiness property. */
    public static final PropertyKey<OperationalState> OPERATIONAL_STATE =
            PropertyKey.of("operationalState");

    /** Typed identity of the current location property. */
    public static final PropertyKey<LocationId> LOCATION_ID =
            PropertyKey.of("locationId");

    private final NodeId timingNodeId;
    private final AntennaId antennaId;
    private final TimeSource timeSource;
    private final LogBook logBook;
    private final NodeService nodeService;
    private final TagProcessor tagProcessor;
    private final SerialTaskRunner taskRunner;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();

    // Current TimingNode state.
    private final PropertyContainer properties = new PropertyContainer();
    private final Property<FunctionalState> functionalStateProp =
            properties.property(FUNCTIONAL_STATE, FunctionalState.CLOSED);
    private final Property<OperationalState> operationalStateProp =
            properties.property(OPERATIONAL_STATE, OperationalState.INIT);
    private final Property<LocationId> locationIdProp =
            properties.property(LOCATION_ID, null);

    private final Object operationLock = new Object();
    private final Object stateLock = new Object();

    private final MainTask mainTask = new MainTask();

    /** Antenna observation input; internally consumed by the TagProcessor. */
    public static final EventInput<TimingNode, TagObservation> TAG_OBSERVATIONS =
            EventInput.of(
                    (node, source) -> source.subscribe(node.tagProcessor::onTagObserved));

    /** Selected registration input from the TagProcessor lane. */
    private static final EventInput<TimingNode, TagProcessor.NewRegistration> NEW_REGISTRATION =
            EventInput.of(
                    (node, source) -> source.subscribe(node::onNewRegistration));

    /** Current properties and their change subscriptions for this TimingNode. */
    public final PropertyContainer PROPERTIES = properties;

    /** Post-commit TimingData produced by this node's LogBook. */
    public final EventSource<TimingData> NEW_DATA_ENTRY;

    /** Creates one TimingNode with its clock and child components. */
    public TimingNode(
            AntennaId antennaId,
            TimeSource timeSource,
            LogBook logBook,
            NodeService nodeService,
            TagProcessor tagProcessor,
            SerialTaskRunner taskRunner) {

        super(
                () -> new IllegalStateException(
                        "TimingNode " + logBook.timingNodeId().value() + " is not active"));

        failIfInvalidArgument(antennaId != null, "antennaId must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");
        failIfInvalidArgument(tagProcessor != null, "tagProcessor must not be null");
        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");

        this.timingNodeId = logBook.timingNodeId();
        this.antennaId = antennaId;
        this.timeSource = timeSource;
        this.logBook = logBook;
        this.nodeService = nodeService;
        this.NEW_DATA_ENTRY = logBook.NEW_DATA_ENTRY;
        this.tagProcessor = tagProcessor;
        this.taskRunner = taskRunner;
        lifecycle.register(nodeService);
        lifecycle.register(logBook);
        lifecycle.register(tagProcessor);

        /*
         * LOADED only signals LogBook readiness. NodeService.MainTask reads the
         * current head and fetches missing TimingData in its own bounded batches.
         */
        NodeService.LOGBOOK_STATUS.bind(
                nodeService,
                logBook.STATUS);
        NodeService.TIMING_DATA_CHANGED.bind(
                nodeService,
                logBook.NEW_DATA_ENTRY);

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

    /**
     * Returns this TimingNode's own runtime readiness.
     *
     * <p>Child-component health is independent. For example, LogBook ERROR
     * does not put the TimingNode itself in ERROR.</p>
     */
    public OperationalState operationalState() {

        return operationalStateProp.get();
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
     * @throws IllegalStateException when the node is not active
     */
    public OpenResult open(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
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
                    nodeService.locationOpened(locationId);
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
            failIfInactive();

            CloseResult result;
            if (functionalStateProp.get() == FunctionalState.CLOSED) {
                result = CloseResult.ALREADY_CLOSED;
            } else {
                LocationId currentLocation = locationIdProp.get();
                synchronized (stateLock) {
                    functionalStateProp.set(FunctionalState.CLOSED);
                    logBook.execute(LogBookCommands.commitNodeClose(currentLocation));
                    nodeService.locationClosed();
                }
                properties.update();
                result = CloseResult.CLOSED;
            }
            return result;
        }
    }

    /**
     * Runs one synchronous result-bearing operation while the node operation lock is held.
     *
     * <p>The supplied operation runs immediately on the caller thread. This method does not
     * queue work and is not a commit/rollback transaction. Application code uses this scope
     * when it must read the current functional state or LocationId and process related
     * LogBook work without {@link #open(LocationId)} or {@link #close()} changing that node
     * state between those steps.</p>
     *
     * <p>The operation lock remains held until the Supplier returns, so supplied work should
     * stay bounded and should represent one node-scoped operation.</p>
     *
     * @param operation synchronous work to execute while the node operation lock is held
     * @return the Supplier result
     */
    public <R> R processLocked(Supplier<R> operation) {

        failIfInvalidArgument(operation != null, "operation must not be null");
        synchronized (operationLock) {
            return operation.get();
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
     * Activates child components and starts the asynchronous node MainTask.
     *
     * <p>Activation returns after the MainTask is admitted. The node remains
     * {@link OperationalState#INIT} while that task loads the LogBook on the
     * node executor.</p>
     */
    @Override
    protected void onActivate() {

        setOperationalState(OperationalState.INIT);
        taskRunner.start();
        lifecycle.activateAll();
        mainTask.start(taskRunner);
    }

    /** Deactivates all owned child components in reverse lifecycle order. */
    @Override
    protected void onDeactivate() {

        taskRunner.close();
        lifecycle.deactivateAll();
        synchronized (stateLock) {
            operationalStateProp.set(OperationalState.INIT);
            functionalStateProp.set(FunctionalState.CLOSED);
            locationIdProp.set(null);
            properties.sync();
        }
    }

    /** Startup flow executed asynchronously on this node's Task Lane. */
    private final class MainTask extends AbstractTask {

        @Override
        protected void resetForRun() {
            // Main task state is owned by TimingNode.
        }

        @Override
        public TaskStep runStep() {
            logBook.loadPersistedData();
            return TaskStep.done();
        }

        @Override
        protected void onRunCompleted(Throwable failure) {
            setOperationalState(
                    failure == null
                            ? OperationalState.OK
                            : OperationalState.ERROR);
        }
    }
    /** Changes runtime readiness without changing OPEN/CLOSED functional state. */
    private void setOperationalState(OperationalState state) {
        synchronized (stateLock) {
            operationalStateProp.set(state);
        }
        properties.update();
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
