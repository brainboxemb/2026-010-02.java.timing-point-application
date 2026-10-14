package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CommandQueueResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Top-level owner for one logical timing node.
 *
 * <p>Runtime composes the LogBook, Task and Tag children before construction.
 * TimingNode owns their lifecycle and the one-way registration link from
 * TagProcessor to LogBook. Child-specific operations stay on their
 * explicit access boundaries rather than being copied onto this class.</p>
 */
public final class TimingNode {
    private static final Logger LOG = LoggerFactory.getLogger(TimingNode.class);

    private final NodeId timingNodeId;
    private final LogBookAccessor logBook;
    private final ComponentLifecycleManager lifecycle =
            new ComponentLifecycleManager();

    public TimingNode(
            LogBook logBook,
            NodeService nodeService,
            TagProcessor tagProcessor) {
        checkArgument(logBook != null, "logBook must not be null");
        checkArgument(nodeService != null, "nodeService must not be null");
        checkArgument(tagProcessor != null, "tagProcessor must not be null");

        this.timingNodeId =
                logBook.timingNodeId();
        this.logBook = logBook;
        lifecycle.register(
                nodeService,
                NodeService::activate,
                NodeService::deactivate);
        lifecycle.register(
                logBook,
                LogBook::activate,
                LogBook::deactivate);
        lifecycle.register(
                tagProcessor,
                TagProcessor::activate,
                TagProcessor::deactivate);

        /*
         * TagProcessor emits synchronously on its own lane. The named handler
         * performs only a non-blocking handoff to the LogBook Data Lane.
         */
        tagProcessor.newRegistrationEvent().subscribe(this::onNewRegistration);
    }

    public NodeId timingNodeId() {
        return timingNodeId;
    }

    /**
     * Returns the node's LogBook access boundary.
     *
     * <p>Callers may submit LogBook commands, run supported queries and
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

    public void deactivate() {
        lifecycle.deactivateAll();
    }

    /**
     * Hands one TagProcessor registration fact to the LogBook without blocking
     * the synchronous source event callback.
     */
    private void onNewRegistration(TagProcessor.NewRegistration registration) {
        CommandQueueResult queueResult =
                logBook.queue(
                        LogBookCommands.addAutomaticRegistration(
                                registration.registrationId(),
                                registration.observedAt()));

        switch (queueResult) {
            case QUEUED:
                return;
            case QUEUE_FULL:
                LOG.error(
                        "LogBook Data Lane queue is full for node {}; automatic registration {} was not queued",
                        timingNodeId.value(),
                        registration.registrationId().value());
                return;
            case NOT_RUNNING:
                LOG.warn(
                        "LogBook Data Lane is not running for node {}; automatic registration {} was not queued",
                        timingNodeId.value(),
                        registration.registrationId().value());
                return;
            default:
                throw new IllegalStateException(
                        "Unsupported LogBook queue result " + queueResult);
        }
    }
}
