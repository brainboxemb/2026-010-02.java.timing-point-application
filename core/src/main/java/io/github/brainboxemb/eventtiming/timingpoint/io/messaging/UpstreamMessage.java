package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Minimal routing envelope used inside Messaging.
 *
 * <p>The envelope separates routing metadata from IF-06 semantics. UpstreamGateway
 * uses only {@link #nodeId()} and treats {@link #payload()} as opaque. The semantic
 * payload is interpreted only after GatewayUpstreamStream delivers it to NodeUpstream.</p>
 */
public final class UpstreamMessage {
    private final NodeId nodeId;
    private final UpstreamData payload;

    private UpstreamMessage(
            NodeId nodeId,
            UpstreamData payload) {
        this.nodeId = nodeId;
        this.payload = payload;
    }

    /**
     * Wraps one node-scoped semantic message for Messaging.
     *
     * <p>This factory is the adaptation point that extracts routing metadata from
     * the semantic contract. Gateway and connector code do not repeat that knowledge.</p>
     */
    public static UpstreamMessage from(
            UpstreamData payload) {
        failIfInvalidArgument(payload instanceof UpstreamData.NodeScoped,
                "UpstreamData must be node-scoped");

        UpstreamData.NodeScoped nodeScoped =
                (UpstreamData.NodeScoped) payload;
        return new UpstreamMessage(
                nodeScoped.timingNodeId(),
                payload);
    }

    /** Returns the routing identity used by UpstreamGateway. */
    public NodeId nodeId() {
        return nodeId;
    }

    /** Returns the semantic payload; routing code must treat this value as opaque. */
    public UpstreamData payload() {
        return payload;
    }
}
