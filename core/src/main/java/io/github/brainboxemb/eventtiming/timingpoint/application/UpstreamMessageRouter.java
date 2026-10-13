package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.UpstreamMessagePort;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application boundary that resolves node-scoped UpstreamData to the owning
 * TimingNode.
 *
 * <p>The router deliberately does not interpret protocol families, sequences
 * or ACK/REQ semantics. After resolving NodeId it delegates the complete
 * semantic message to the node-owned UpstreamMessagePort.</p>
 */
public final class UpstreamMessageRouter {
    private final Map<NodeId, UpstreamMessagePort> nodePorts;

    public UpstreamMessageRouter(
            TimingNodeList timingNodes) {
        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "timingNodes must contain at least one TimingNode");
        }

        Map<NodeId, UpstreamMessagePort> ports =
                new LinkedHashMap<NodeId, UpstreamMessagePort>();
        for (TimingNode timingNode : timingNodes) {
            NodeId nodeId = timingNode.timingNodeId();
            if (ports.put(
                    nodeId,
                    timingNode.upstreamMessagePort()) != null) {
                throw new IllegalArgumentException(
                        "Duplicate TimingNode id "
                                + nodeId.value());
            }
        }
        nodePorts =
                Collections.unmodifiableMap(
                        ports);
    }

    /**
     * Routes one node-scoped semantic message and returns any semantic outbound
     * messages caused by handling it.
     */
    public List<UpstreamData> route(
            UpstreamData data) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null");
        }
        if (!(data instanceof UpstreamData.NodeScoped)) {
            throw new IllegalArgumentException(
                    "UpstreamData is not node-scoped: "
                            + data.family());
        }

        UpstreamData.NodeScoped nodeData =
                (UpstreamData.NodeScoped) data;
        UpstreamMessagePort port =
                nodePorts.get(
                        nodeData.timingNodeId());
        if (port == null) {
            throw new IllegalArgumentException(
                    "Unknown TimingNode "
                            + nodeData.timingNodeId().value());
        }

        return port.handle(
                nodeData);
    }
}
