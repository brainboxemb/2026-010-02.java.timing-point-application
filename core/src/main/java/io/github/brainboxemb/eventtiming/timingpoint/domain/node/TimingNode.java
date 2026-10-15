package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommand;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
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

    /** Current status determined by this TimingNode from its component facts. */
    private volatile Status currentStatus;

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
        logBook.statusChangedEvent().subscribe(this::onLogBookStatusChanged);

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
         * Before the first LogBook status event, preserve the LogBook's
         * availability/failure semantics while still evaluating the result at
         * the TimingNode boundary.
         */
        return evaluateStatus(
                logBook.process(
                        LogBookQueries.status(),
                        ReadConsistency.CURRENT));
    }

    /** Publishes TimingNode status after this component has evaluated it. */
    public EventSource<Status> statusChangedEvent() {

        return statusChangedEvent;
    }

    /** Opens this TimingNode at the requested timing location. */
    public OpenResult open(LocationId locationId) {

        return logBook.process(LogBookCommands.open(locationId));
    }

    /** Closes this TimingNode. */
    public CloseResult close() {

        return logBook.process(LogBookCommands.close());
    }

    /**
     * Returns the node's LogBook access boundary.
     *
     * <p>Callers may execute or queue LogBook commands, run supported queries and
     * subscribe to post-commit/status events. Child lifecycle, persisted-data
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
        currentStatus = null;
    }

    /**
     * Re-evaluates node status after the LogBook's recorded state changes.
     *
     * <p>The LogBook reports what its committed/reconstructed history says.
     * TimingNode owns the externally meaningful node status and may combine
     * additional component facts here as the node model grows.</p>
     */
    private void onLogBookStatusChanged(Status logBookStatus) {

        Status evaluated = evaluateStatus(logBookStatus);
        Status previous = currentStatus;
        currentStatus = evaluated;

        if (!evaluated.equals(previous)) {
            statusChangedEvent.emit(evaluated);
        }
    }

    /** Determines TimingNode status from the currently available component facts. */
    private Status evaluateStatus(Status logBookStatus) {

        return new Status(
                timingNodeId,
                logBookStatus.state(),
                logBookStatus.locationId(),
                logBookStatus.timingDataTailRepaired(),
                logBookStatus.problems());
    }

    /**
     * Hands one TagProcessor registration fact to the LogBook without blocking
     * the synchronous source event callback.
     */
    private void onNewRegistration(TagProcessor.NewRegistration registration) {

        LogBookCommand<?> command = LogBookCommands.addAutomaticRegistration(
                registration.registrationId(), registration.observedAt());

        Runnable queueRegistration = () -> logBook.execute(command);
        Consumer<RejectedExecutionException> onRejected = ex -> LOG.warn(
                "Automatic registration {} for node {} could not be queued on the LogBook Data Lane",
                registration.registrationId().value(),
                timingNodeId.value(),
                ex);

        SafeExecute.runOrHandleRejection(queueRegistration, onRejected);
    }
}
