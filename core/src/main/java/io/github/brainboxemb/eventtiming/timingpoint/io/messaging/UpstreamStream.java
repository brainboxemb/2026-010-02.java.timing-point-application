package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

/**
 * Non-blocking bidirectional semantic mailbox owned by one NodeUpstream.
 *
 * <p>The stream is already scoped to one NodeId. Real transport I/O stays
 * below UpstreamGateway and never becomes NodeUpstream protocol state.</p>
 */
public interface UpstreamStream {

    /** Returns the default unavailable stream used when no transport is composed. */
    static UpstreamStream unavailable() {
        return UnavailableUpstreamStream.INSTANCE;
    }

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
