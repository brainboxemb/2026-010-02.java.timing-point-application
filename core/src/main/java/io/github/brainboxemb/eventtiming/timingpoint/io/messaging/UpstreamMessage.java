package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Minimal routing envelope used inside Messaging.
 *
 * <p>The gateway uses only {@link #nodeId()} and treats {@link #payload()} as
 * opaque encoded data. Protocol-specific parsing belongs outside the generic
 * routing layer.</p>
 */
public final class UpstreamMessage {
    private final NodeId nodeId;
    private final String payload;

    public UpstreamMessage(
            NodeId nodeId,
            String payload) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(payload != null, "payload must not be null");
        this.nodeId = nodeId;
        this.payload = payload;
    }

    /** Returns the routing identity used by UpstreamGateway. */
    public NodeId nodeId() {
        return nodeId;
    }

    /** Returns the encoded payload; routing code must treat this value as opaque. */
    public String payload() {
        return payload;
    }
}
