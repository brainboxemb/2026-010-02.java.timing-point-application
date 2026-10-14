package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeTaskProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top-level owner for one logical timing node.
 *
 * <p>Runtime composes the LogBook, Task and Tag children before construction.
 * TimingNode owns their lifecycle and the one-way registration link from
 * TagProcessor to LogBookProcessor. Child-specific operations stay on their
 * explicit access boundaries rather than being copied onto this class.</p>
 */
public final class TimingNode {
    private static final Logger LOG = LoggerFactory.getLogger(TimingNode.class);

    private final NodeId timingNodeId;
    private final LogBookAccessor logBook;
    private final NodeTaskProcessor taskProcessor;
    private final ComponentLifecycleManager lifecycle =
            new ComponentLifecycleManager();

    public TimingNode(
            LogBookProcessor logBookProcessor,
            NodeTaskProcessor taskProcessor,
            TagProcessor tagProcessor) {
        if (logBookProcessor == null) {
            throw new IllegalArgumentException(
                    "logBookProcessor must not be null");
        }
        if (taskProcessor == null) {
            throw new IllegalArgumentException(
                    "taskProcessor must not be null");
        }
        if (tagProcessor == null) {
            throw new IllegalArgumentException(
                    "tagProcessor must not be null");
        }

        this.timingNodeId =
                logBookProcessor.timingNodeId();
        this.logBook = logBookProcessor;
        this.taskProcessor = taskProcessor;

        lifecycle.register(
                "LogBookProcessor",
                logBookProcessor::activate,
                logBookProcessor::deactivate);
        lifecycle.register(
                "NodeTaskProcessor",
                taskProcessor::activate,
                taskProcessor::deactivate);
        lifecycle.register(
                "TagProcessor",
                tagProcessor::activate,
                tagProcessor::deactivate);

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
     * subscribe to post-commit/status events. Processor lifecycle, recovery,
     * lane ownership and metrics remain internal to the node composition.</p>
     */
    public LogBookAccessor logBook() {
        return logBook;
    }

    public void activate() {
        /*
         * LogBook activation starts asynchronous recovery immediately. Check
         * the Task Lane first so a known Task Lane lifecycle error cannot occur
         * only after recovery has already started. Ordered child activation,
         * rollback and reverse deactivation remain owned by the lifecycle manager.
         */
        taskProcessor.validateActivationBeforeRecovery();
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
        CommandAdmission admission = logBook.offer(
                LogBookCommands.addAutomaticRegistration(
                        registration.registrationId(),
                        registration.observedAt()));

        switch (admission) {
            case ACCEPTED:
                return;
            case FULL:
                LOG.error(
                        "LogBook Data Lane is full for node {}; automatic registration {} was not accepted",
                        timingNodeId.value(),
                        registration.registrationId().value());
                return;
            case NOT_RUNNING:
                LOG.warn(
                        "LogBook Data Lane is not running for node {}; automatic registration {} was not accepted",
                        timingNodeId.value(),
                        registration.registrationId().value());
                return;
            default:
                throw new IllegalStateException("Unsupported LogBook admission result " + admission);
        }
    }
}
