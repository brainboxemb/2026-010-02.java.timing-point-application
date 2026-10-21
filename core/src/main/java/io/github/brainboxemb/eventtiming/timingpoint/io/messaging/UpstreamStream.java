package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

/**
 * NodeUpstream's non-blocking semantic view of upstream communication.
 *
 * <p>NodeUpstream reads and writes {@link UpstreamData}; it never sees connector
 * sessions, routing envelopes or transport queues. The gateway-backed implementation
 * performs that adaptation at the I/O boundary.</p>
 */
public interface UpstreamStream {

    /** Returns one node-scoped semantic view over a shared system gateway. */
    static UpstreamStream forNode(
            UpstreamGateway gateway,
            NodeId nodeId) {
        return new GatewayUpstreamStream(nodeId, gateway);
    }

    /** Returns whether the upstream communication/session is currently usable. */
    boolean isAvailable();

    /** Returns whether one inbound semantic message can be read without blocking. */
    boolean hasMessage();

    /**
     * Consumes one inbound semantic message.
     *
     * @throws IllegalStateException when no message is available.
     */
    UpstreamData read();

    /**
     * Enqueues one outbound semantic message without performing blocking
     * transport I/O.
     */
    void send(UpstreamData message);
}
