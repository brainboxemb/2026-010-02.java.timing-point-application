package oss.relay.timing.timingpoint.application;

import oss.relay.timing.eventdata.EventData;
import oss.relay.timing.timingpoint.domain.node.TimingNode;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookAccessor;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookBackup;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookTestControl;
import oss.relay.timing.timingpoint.domain.node.service.state.NodeStateAccessor;
import oss.relay.timing.timingpoint.domain.node.service.state.NodeStateTestControl;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Explicit composition input for one presentation-facing TimingNode proxy.
 *
 * <p>The value carries only boundaries used by Presentation. Runtime keeps NodeService
 * internal and supplies EventData plus the normal and engineering state boundaries explicitly.</p>
 */
public final class TimingNodeProxyComponents {
    private final TimingNode timingNode;
    private final EventData eventData;
    private final NodeStateAccessor nodeState;
    private final NodeStateTestControl nodeStateTestControl;
    private final LogBookAccessor logBook;
    private final LogBookTestControl logBookTestControl;
    private final LogBookBackup logBookBackup;

    /** Creates one immutable proxy-composition value. */
    public TimingNodeProxyComponents(
            TimingNode timingNode,
            EventData eventData,
            NodeStateAccessor nodeState,
            NodeStateTestControl nodeStateTestControl,
            LogBookAccessor logBook,
            LogBookTestControl logBookTestControl,
            LogBookBackup logBookBackup) {

        failIfInvalidArgument(timingNode != null, "timingNode must not be null");
        failIfInvalidArgument(eventData != null, "eventData must not be null");
        failIfInvalidArgument(nodeState != null, "nodeState must not be null");
        failIfInvalidArgument(nodeStateTestControl != null, "nodeStateTestControl must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(
                logBookTestControl != null,
                "logBookTestControl must not be null");
        failIfInvalidArgument(
                logBookBackup != null,
                "logBookBackup must not be null");
        this.timingNode = timingNode;
        this.eventData = eventData;
        this.nodeState = nodeState;
        this.nodeStateTestControl = nodeStateTestControl;
        this.logBook = logBook;
        this.logBookTestControl = logBookTestControl;
        this.logBookBackup = logBookBackup;
    }

    TimingNode timingNode() {
        return timingNode;
    }

    EventData eventData() {
        return eventData;
    }

    NodeStateAccessor nodeState() {
        return nodeState;
    }

    NodeStateTestControl nodeStateTestControl() {
        return nodeStateTestControl;
    }

    LogBookAccessor logBook() {
        return logBook;
    }

    LogBookTestControl logBookTestControl() {
        return logBookTestControl;
    }

    LogBookBackup logBookBackup() {
        return logBookBackup;
    }
}
