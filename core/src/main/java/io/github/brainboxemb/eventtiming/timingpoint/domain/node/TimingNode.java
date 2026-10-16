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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;

/**
 * Top-level owner for one logical timing node.
 *
 * <p>Runtime constructs the LogBook, Task and Tag children before construction.
 * TimingNode owns their lifecycle and node-internal event links. The containing
 * TimingSystem binds typed event sources to this component without exposing
 * TagProcessor or other child objects.</p>
 */
public final class TimingNode {
    private static final Logger LOG = LoggerFactory.getLogger(TimingNode.class);
    private final NodeId timingNodeId;
    private final AntennaId antennaId;
    private final LogBookAccessor logBook;
    private final TagProcessor tagProcessor;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();
    private final Event<Status> statusChangedEvent = new Event<Status>();
    private final Object operationLock = new Object();
    private final Object stateLock = new Object();

    /** Current immutable node-state snapshot; null until LogBook recovery completes. */
    private volatile Status currentStatus;
    private boolean registrationsEnabled;

    /** Antenna observation input; internally consumed by the TagProcessor. */
    public static final EventInput<TimingNode, TagObservation> TAG_OBSERVATIONS =
            EventInput.of(
                    (node, source) -> source.subscribe(node.tagProcessor::onTagObserved));

    /** Creates the top-level node owner around its already composed child components. */
    public TimingNode(
            AntennaId antennaId,
            LogBook logBook,
            NodeService nodeService,
            TagProcessor tagProcessor) {

        checkArgument(antennaId != null, "antennaId must not be null");
        checkArgument(logBook != null, "logBook must not be null");
        checkArgument(nodeService != null, "nodeService must not be null");
        checkArgument(tagProcessor != null, "tagProcessor must not be null");

        this.timingNodeId = logBook.timingNodeId();
        this.antennaId = antennaId;
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

        /*
         * TagProcessor emits synchronously on its own lane. The named handler
         * performs only a non-blocking handoff to the LogBook Data Lane.
         */
        tagProcessor.newRegistrationEvent().subscribe(this::onNewRegistration);
    }

    /**
     * Binds one typed event source to a supported TimingNode input.
     *
     * <p>Binding is a composition-time operation. The caller selects the semantic
     * input and supplies the source; TimingNode owns the internal subscription and
     * does not expose the child component that consumes the event.</p>
     */
    public <T> void bind(EventInput<TimingNode, T> input, EventSource<T> source) {

        checkArgument(input != null, "input must not be null");
        checkArgument(source != null, "source must not be null");
        input.bind(this, source);
    }

    /** Returns the immutable identity of this logical timing node. */
    public NodeId timingNodeId() {

        return timingNodeId;
    }

    /** Returns the resolved antenna identity for this TimingNode. */
    public AntennaId antennaId() {

        return antennaId;
    }

    /** Returns the current status determined by this TimingNode. */
    public Status status() {

        Status status = currentStatus;
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

    /** Publishes TimingNode status after this component has evaluated it. */
    public EventSource<Status> statusChangedEvent() {

        return statusChangedEvent;
    }

    /** Opens this TimingNode at the requested timing location. */
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

    /** Closes this TimingNode while retaining the last timing location. */
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

    /** Commits one normal automatic registration when this TimingNode is open. */
    public RegistrationResult addAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime) {

        synchronized (operationLock) {
            Status status = requireOperationalStatus();
            if (status.state() != State.OPEN) {
                return RegistrationResult.nodeNotOpen();
            }
            return logBook.process(
                    LogBookCommands.addAutomaticRegistration(
                            status.locationId(),
                            registrationId,
                            effectiveTime));
        }
    }

    /** Commits one engineering automatic registration with explicit provenance. */
    public RegistrationResult commitAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingData.TagSource tagSource,
            TimingData.AutomaticTimeSource timeSource) {

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
                            tagSource,
                            timeSource));
        }
    }

    /** Commits one engineering automatic registration using node time. */
    public RegistrationResult commitAutomaticRegistrationNow(
            RegistrationId registrationId,
            TimingData.TagSource tagSource) {

        synchronized (operationLock) {
            Status status = requireOperationalStatus();
            if (status.state() != State.OPEN) {
                return RegistrationResult.nodeNotOpen();
            }
            return logBook.process(
                    LogBookCommands.commitAutomaticRegistrationNow(
                            status.locationId(),
                            registrationId,
                            tagSource));
        }
    }

    /** Commits one manual registration when this TimingNode is open. */
    public RegistrationResult addManualRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource timeSource) {

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
                            timeSource));
        }
    }

    /**
     * Returns the node's LogBook access boundary.
     *
     * <p>Callers may execute or queue LogBook commands, run supported queries and
     * subscribe to post-commit/recovery events. Child lifecycle, persisted-data
     * startup loading, lane ownership and metrics remain internal to the node composition.</p>
     */
    public LogBookAccessor logBook() {

        return logBook;
    }

    /**
     * Activates the node-owned execution contexts in dependency order.
     *
     * <p>The Task Lane starts first because its lifecycle is independent of
     * LogBook state and it consumes initial TimingData after that data has been
     * loaded. LogBook then starts the Data Lane and queues the persisted
     * TimingData load as its first ordered task. TagProcessor ingress starts last.
     * Rollback and reverse deactivation are owned by the lifecycle manager.</p>
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

    /** Applies newly committed lifecycle records to this node's runtime state. */
    private void onCommittedTimingData(TimingData timingData) {

        Status status = currentStatus;
        if (status == null) {
            return;
        }

        if (timingData instanceof TimingData.NodeOpen) {
            setStatus(new Status(
                    timingNodeId,
                    State.OPEN,
                    timingData.locationId(),
                    status.timingDataTailRepaired(),
                    status.problems()));
        } else if (timingData instanceof TimingData.NodeClose) {
            setStatus(new Status(
                    timingNodeId,
                    State.CLOSED,
                    timingData.locationId(),
                    status.timingDataTailRepaired(),
                    status.problems()));
        }
    }

    /** Publishes the already-applied lifecycle status after committed-data delivery. */
    private void onLifecycleCommitPublished(TimingData timingData) {

        Status status = currentStatus;
        if (status != null) {
            statusChangedEvent.emit(status);
        }
    }

    /** Applies LogBook recovery facts to the node's initial runtime status. */
    private void onLogBookRecoveryCompleted(LogBookRecoveryStatus recoveryStatus) {

        publishStatus(statusFromRecovery(recoveryStatus));
    }

    /** Builds initial node status from LogBook recovery without reconstructing OPEN state. */
    private Status statusFromRecovery(LogBookRecoveryStatus recoveryStatus) {

        State state = recoveryStatus.hasProblems() ? State.ERROR : State.CLOSED;
        return new Status(
                timingNodeId,
                state,
                null,
                recoveryStatus.timingDataTailRepaired(),
                recoveryStatus.problems());
    }

    /**
     * Returns status for a state-changing operation.
     *
     * <p>When startup recovery is still queued, the first state-changing call
     * waits for that ordered LogBook recovery result. Plain {@link #status()}
     * remains a non-waiting CURRENT read.</p>
     */
    private Status requireOperationalStatus() {

        Status status = currentStatus;
        if (status == null) {
            LogBookRecoveryStatus recoveryStatus =
                    logBook.process(
                            LogBookQueries.recoveryStatus(),
                            ReadConsistency.ORDERED);
            status = currentStatus;
            if (status == null) {
                status = statusFromRecovery(recoveryStatus);
                publishStatus(status);
            }
        }

        if (status.state() == State.ERROR) {
            String detail = status.hasProblems()
                    ? status.problems().get(0).message()
                    : "TimingNode is in ERROR";
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    detail);
        }
        return status;
    }

    /** Stores one new immutable node-state snapshot. */
    private void setStatus(Status status) {

        synchronized (stateLock) {
            currentStatus = status;
            registrationsEnabled = status.state() == State.OPEN;
        }
    }

    /** Stores and publishes one new immutable node-state snapshot. */
    private void publishStatus(Status status) {

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
     * Hands one TagProcessor registration fact to the LogBook without blocking
     * the synchronous source event callback.
     */
    private void onNewRegistration(TagProcessor.NewRegistration registration) {

        synchronized (stateLock) {
            Status status = currentStatus;
            if (!registrationsEnabled || status == null || status.state() != State.OPEN) {
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
