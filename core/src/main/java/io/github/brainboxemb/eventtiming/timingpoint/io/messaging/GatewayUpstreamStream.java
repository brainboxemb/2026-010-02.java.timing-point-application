package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Adapts one NodeUpstream's semantic stream to the shared routing gateway.
 *
 * <p>This is the boundary where semantic {@link UpstreamData} becomes the small
 * {@link UpstreamMessage} envelope used by Messaging. UpstreamGateway therefore
 * never needs to inspect the IF-06 type hierarchy.</p>
 */
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
        UpstreamMessage message =
                gateway.read(nodeId);
        failIfIllegalState(nodeId.equals(message.nodeId()),
                "UpstreamGateway routed node %s data to node %s",
                message.nodeId().value(),
                nodeId.value());
        return message.payload();
    }

    @Override
    public void send(
            UpstreamData payload) {
        UpstreamMessage message =
                UpstreamMessage.from(payload);
        failIfInvalidArgument(nodeId.equals(message.nodeId()),
                "Outbound UpstreamData NodeId does not match node stream");
        gateway.send(message);
    }
}
