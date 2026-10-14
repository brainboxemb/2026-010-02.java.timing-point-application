package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeTaskProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;


/**
 * Top-level owner for one logical timing node.
 *
 * <p>Runtime composes the LogBook, Task and Tag children before construction.
 * TimingNode owns their lifecycle and the one-way registration link from
 * TagProcessor to LogBookProcessor. Child-specific operations stay on their
 * explicit access boundaries rather than being copied onto this class.</p>
 */
public final class TimingNode {
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

        tagProcessor.newRegistrationEvent()
                .subscribe(
                        registration ->
                                logBook.offer(
                                        LogBookCommands.addAutomaticRegistration(
                                                registration.registrationId(),
                                                registration.observedAt())));
    }

    public NodeId timingNodeId() {
        return timingNodeId;
    }

    /** Returns the node's intentional LogBook command/query/event boundary. */
    public LogBookAccessor logBook() {
        return logBook;
    }

    public void activate() {
        taskProcessor.ensureCanActivate();
        lifecycle.activateAll();
    }

    public void deactivate() {
        lifecycle.deactivateAll();
    }

}
