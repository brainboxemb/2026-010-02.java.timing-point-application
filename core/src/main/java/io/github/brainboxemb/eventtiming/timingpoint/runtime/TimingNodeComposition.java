package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;

/**
 * Runtime-only construction result for one TimingNode.
 *
 * <p>The Domain exposes TimingNode as the system child. Runtime retains the
 * already constructed NodeService and LogBook only long enough to compose the
 * separate TimingSystem-owned upstream protocol component.</p>
 */
final class TimingNodeComposition {
    private final TimingNode node;
    private final NodeService nodeService;
    private final LogBook logBook;

    TimingNodeComposition(
            TimingNode node,
            NodeService nodeService,
            LogBook logBook) {
        this.node = node;
        this.nodeService = nodeService;
        this.logBook = logBook;
    }

    TimingNode node() {
        return node;
    }

    NodeService nodeService() {
        return nodeService;
    }

    LogBook logBook() {
        return logBook;
    }
}
