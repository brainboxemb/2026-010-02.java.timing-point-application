package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/** Bidirectional semantic view owned by one NodeUpstream over UpstreamGateway. */
final class GatewayUpstreamStream implements UpstreamStream {
    private final NodeId nodeId;
    private final UpstreamGateway gateway;

    GatewayUpstreamStream(
            NodeId nodeId,
            UpstreamGateway gateway) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(gateway != null, "gateway must not be null");
        this.nodeId = nodeId;
        this.gateway = gateway;
    }

    @Override
    public boolean isAvailable() {
        return gateway.isAvailable(nodeId);
    }

    @Override
    public boolean hasMessage() {
        return gateway.hasMessage(nodeId);
    }

    @Override
    public UpstreamData read() {
        UpstreamData message = gateway.read(nodeId);
        failIfIllegalState(
                message instanceof UpstreamData.NodeScoped,
                "UpstreamGateway returned non-node-scoped data for node %s",
                nodeId.value());

        UpstreamData.NodeScoped nodeScoped =
                (UpstreamData.NodeScoped) message;
        failIfIllegalState(
                nodeId.equals(nodeScoped.timingNodeId()),
                "UpstreamGateway routed node %s data to node %s",
                nodeScoped.timingNodeId().value(),
                nodeId.value());
        return message;
    }

    @Override
    public void send(UpstreamData message) {
        failIfInvalidArgument(
                message instanceof UpstreamData.NodeScoped,
                "Outbound upstream data must be node-scoped");

        UpstreamData.NodeScoped nodeScoped =
                (UpstreamData.NodeScoped) message;
        failIfInvalidArgument(
                nodeId.equals(nodeScoped.timingNodeId()),
                "Outbound UpstreamData NodeId does not match node stream");
        gateway.send(message);
    }
}
