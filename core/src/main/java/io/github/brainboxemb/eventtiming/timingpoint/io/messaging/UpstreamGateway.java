package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

/**
 * System-level transport boundary for upstream communication.
 *
 * <p>Inbound transport is node-scoped: a concrete gateway may bind one queue
 * or consumer per TimingNode. Outbound transport is shared: every node sends
 * through the same system output boundary and the message's NodeId identifies
 * its source.</p>
 *
 * <p>Connection/session ownership belongs to the concrete gateway. Domain
 * upstream protocol state never owns a RabbitMQ/TCP connection.</p>
 */
public interface UpstreamGateway {

    /** Returns the default unavailable gateway used when no transport is composed. */
    static UpstreamGateway unavailable() {
        return UnavailableUpstreamGateway.INSTANCE;
    }

    /** Returns whether the shared upstream connection/session is usable. */
    boolean isAvailable();

    /** Returns whether one message is ready on this node's inbound queue. */
    boolean hasMessage(NodeId nodeId);

    /** Consumes one message from this node's inbound queue. */
    UpstreamData read(NodeId nodeId);

    /** Enqueues one node-scoped message on the shared outbound boundary. */
    void send(UpstreamData message);
}
