package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.NodeUpstream;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * TimingSystem-owned upstream protocol component.
 *
 * <p>One node-scoped NodeUpstream is retained for every TimingNode. Each NodeUpstream therefore owns independent
 * TIMING_DATA, RACE_DATA and heartbeat protocol state. The matching UpstreamStream references are retained only so
 * the owning TimingSystem can install the fixed system-level event links before activation.</p>
 *
 * <p>Each NodeUpstream owns a separate logical task lane backed by the same Runtime-supplied scheduled executor.
 * NodeService applies RACE_DATA separately; the node upstream protocol awaits successful completion before ACK.</p>
 */
public final class Upstream extends LifecycleComponent {
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();
    private final Map<NodeId, NodeUpstream> nodes = new LinkedHashMap<NodeId, NodeUpstream>();
    private final Map<NodeId, UpstreamStream> streams = new LinkedHashMap<NodeId, UpstreamStream>();

    /** Creates an empty/no-transport upstream group, primarily for compositions without upstream nodes. */
    public Upstream(List<NodeUpstream> nodeUpstreams) {
        this(nodeUpstreams, Collections.<NodeId, UpstreamStream>emptyMap());
    }

    /** Creates one system-owned upstream group with its already constructed node application ports. */
    public Upstream(List<NodeUpstream> nodeUpstreams, Map<NodeId, UpstreamStream> nodeStreams) {
        failIfInvalidArgument(nodeUpstreams != null, "nodeUpstreams must not be null");
        failIfInvalidArgument(nodeStreams != null, "nodeStreams must not be null");

        for (NodeUpstream nodeUpstream : nodeUpstreams) {
            failIfInvalidArgument(nodeUpstream != null, "nodeUpstreams must not contain null");

            NodeId nodeId = nodeUpstream.nodeId();
            failIfInvalidArgument(!nodes.containsKey(nodeId), "duplicate upstream node %s", nodeId.value());

            nodes.put(nodeId, nodeUpstream);
            lifecycle.register("NodeUpstream " + nodeId.value(), nodeUpstream);
        }

        for (Map.Entry<NodeId, UpstreamStream> entry : nodeStreams.entrySet()) {
            failIfInvalidArgument(entry.getKey() != null, "nodeStreams must not contain a null NodeId");
            failIfInvalidArgument(entry.getValue() != null, "nodeStreams must not contain a null stream");
            failIfInvalidArgument(nodes.containsKey(entry.getKey()),
                    "UpstreamStream has no matching NodeUpstream %s", entry.getKey().value());

            streams.put(entry.getKey(), entry.getValue());
        }

        failIfInvalidArgument(streams.isEmpty() || streams.size() == nodes.size(),
                "Every NodeUpstream must have one UpstreamStream when streams are supplied");
    }

    /** Returns one node-scoped semantic upstream endpoint owned by this system. */
    public NodeUpstream node(NodeId nodeId) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        return nodes.get(nodeId);
    }

    /** Returns the fixed messaging application port paired with one node-scoped endpoint. */
    public UpstreamStream stream(NodeId nodeId) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        return streams.get(nodeId);
    }

    @Override
    protected void onActivate() {
        lifecycle.activateAll();
    }

    @Override
    protected void onDeactivate() {
        lifecycle.deactivateAll();
    }
}
