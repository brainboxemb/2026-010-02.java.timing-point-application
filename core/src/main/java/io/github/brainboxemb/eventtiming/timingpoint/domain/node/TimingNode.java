package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommand;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Top-level owner for one logical timing node.
 *
 * <p>Runtime composes the LogBook, Task and Tag children before construction.
 * TimingNode owns their lifecycle and node-internal event links. System-level
 * routing talks to this component boundary rather than reaching directly into
 * TagProcessor or other child objects.</p>
 */
public final class TimingNode {
    private static final Logger LOG = LoggerFactory.getLogger(TimingNode.class);
    private final NodeId timingNodeId;
    private final LogBookAccessor logBook;
    private final TagProcessor tagProcessor;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();

    /** Creates the top-level node owner around its already composed child components. */
    public TimingNode(LogBook logBook, NodeService nodeService, TagProcessor tagProcessor) {

        checkArgument(logBook != null, "logBook must not be null");
        checkArgument(nodeService != null, "nodeService must not be null");
        checkArgument(tagProcessor != null, "tagProcessor must not be null");

        this.timingNodeId = logBook.timingNodeId();
        this.logBook = logBook;
        this.tagProcessor = tagProcessor;
        lifecycle.register(nodeService, NodeService::activate, NodeService::deactivate);
        lifecycle.register(logBook, LogBook::activate, LogBook::deactivate);
        lifecycle.register(tagProcessor, TagProcessor::activate, TagProcessor::deactivate);

        /*
         * LogBook emits committed/startup TimingData synchronously on the Data
         * Lane. NodeService handlers only queue the corresponding Task Lane work.
         */
        logBook.initialDataLoadedEvent().subscribe(nodeService::onInitialTimingDataLoaded);
        logBook.newDataEntryEvent().subscribe(nodeService::onTimingDataCommitted);

        /*
         * TagProcessor emits synchronously on its own lane. The named handler
         * performs only a non-blocking handoff to the LogBook Data Lane.
         */
        tagProcessor.newRegistrationEvent().subscribe(this::onNewRegistration);
    }

    /** Accepts one system-routed antenna observation at the TimingNode boundary. */
    public void onTagObserved(TagObservation observation) {

        tagProcessor.onTagObserved(observation);
    }

    /** Returns the immutable identity of this logical timing node. */
    public NodeId timingNodeId() {

        return timingNodeId;
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
