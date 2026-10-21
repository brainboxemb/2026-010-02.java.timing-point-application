package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

/**
 * Transport/session boundary used by UpstreamGateway.
 *
 * <p>A connector owns transport resources and session mechanics. It does not
 * own node queues or interpret UpstreamData protocol semantics. Inbound semantic
 * messages and connection lifecycle signals are handed to the gateway listener.</p>
 */
public interface UpstreamConnector {

    /** Gateway callback installed once during runtime composition. */
    interface Listener {
        UpstreamGateway.ReceiveResult receive(UpstreamData message);

        void connected();

        void disconnected();

        void heartbeatTick();
    }

    /** Binds this connector to one gateway before the transport session starts. */
    void bind(Listener listener);

    /** Returns whether this connector currently has an operational transport session. */
    boolean isAvailable();

    /** Sends one semantic UpstreamData message through the active transport session. */
    void send(UpstreamData message);
}
