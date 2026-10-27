package oss.relay.timing.timingpoint.runtime;

import oss.relay.timing.eventdata.EventData;
import oss.relay.timing.timingpoint.application.TimingNodeProxyComponents;
import oss.relay.timing.timingpoint.domain.node.TimingNode;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBook;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookBackup;
import oss.relay.timing.timingpoint.domain.node.service.NodeService;

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
    private final LogBookBackup logBookBackup;

    TimingNodeComposition(
            TimingNode node,
            NodeService nodeService,
            LogBook logBook,
            LogBookBackup logBookBackup) {
        this.node = node;
        this.nodeService = nodeService;
        this.logBook = logBook;
        this.logBookBackup = logBookBackup;
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

    /** Returns explicit application-facing component boundaries for this node. */
    TimingNodeProxyComponents presentationComponents(EventData eventData) {

        return new TimingNodeProxyComponents(
                node,
                eventData,
                nodeService.state(),
                nodeService.stateTestControl(),
                logBook,
                logBook,
                logBookBackup);
    }
}

