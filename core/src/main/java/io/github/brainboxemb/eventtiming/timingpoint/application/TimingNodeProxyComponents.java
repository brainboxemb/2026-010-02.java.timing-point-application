package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Explicit composition input for one presentation-facing TimingNode proxy.
 *
 * <p>The value keeps the owning TimingNode, NodeService and LogBook boundaries separate.
 * It owns no lifecycle and adds no delegation; Runtime constructs it from the already
 * composed node graph and Presentation uses each component only for its own responsibility.</p>
 */
public final class TimingNodeProxyComponents {
    private final TimingNode timingNode;
    private final NodeService nodeService;
    private final LogBookAccessor logBook;

    /** Creates one immutable proxy-composition value. */
    public TimingNodeProxyComponents(
            TimingNode timingNode,
            NodeService nodeService,
            LogBookAccessor logBook) {

        failIfInvalidArgument(timingNode != null, "timingNode must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        this.timingNode = timingNode;
        this.nodeService = nodeService;
        this.logBook = logBook;
    }

    TimingNode timingNode() {
        return timingNode;
    }

    NodeService nodeService() {
        return nodeService;
    }

    LogBookAccessor logBook() {
        return logBook;
    }
}
