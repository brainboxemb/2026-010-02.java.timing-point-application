package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.NodeUpstream;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGateway;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * TimingSystem-owned upstream protocol component.
 *
 * <p>One node-scoped NodeUpstream is retained for every TimingNode. Each
 * NodeUpstream therefore owns independent TIMING_DATA, RACE_DATA and heartbeat
 * protocol state. Node-scoped queues and connector routing belong to the
 * shared UpstreamGateway; transport sessions remain connector-owned.</p>
 *
 * <p>Each NodeUpstream owns a separate logical task lane backed by the same
 * Runtime-supplied scheduled executor. NodeService applies RACE_DATA separately;
 * the node upstream protocol awaits successful completion before ACK.</p>
 */
public final class Upstream extends LifecycleComponent {
    private final UpstreamGateway gateway;
    private final ComponentLifecycleManager lifecycle =
            new ComponentLifecycleManager();
    private final Map<NodeId, NodeUpstream> nodes =
            new LinkedHashMap<NodeId, NodeUpstream>();

    public Upstream(
            UpstreamGateway gateway,
            List<NodeUpstream> nodeUpstreams) {

        failIfInvalidArgument(gateway != null, "gateway must not be null");
        failIfInvalidArgument(nodeUpstreams != null, "nodeUpstreams must not be null");
        this.gateway = gateway;

        for (NodeUpstream nodeUpstream : nodeUpstreams) {
            failIfInvalidArgument(nodeUpstream != null, "nodeUpstreams must not contain null");
            NodeId nodeId = nodeUpstream.nodeId();
            failIfInvalidArgument(!nodes.containsKey(nodeId),
                    "duplicate upstream node %s", nodeId.value());
            nodes.put(nodeId, nodeUpstream);
            lifecycle.register("NodeUpstream " + nodeId.value(), nodeUpstream);
        }
    }

    /**
     * Initial transport reconciliation.
     *
     * <p>If the gateway is already connected when TimingSystem activation
     * reaches Upstream, publish every node's committed TIMING_DATA source head.</p>
     */
    @Override
    protected void onActivate() {

        lifecycle.activateAll();
        for (Map.Entry<NodeId, NodeUpstream> entry : nodes.entrySet()) {
            if (gateway.isAvailable(entry.getKey())) {
                entry.getValue().sendTimingDataSync();
            }
        }
    }

    @Override
    protected void onDeactivate() {

        lifecycle.deactivateAll();
    }

}
