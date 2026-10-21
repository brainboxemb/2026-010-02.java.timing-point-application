package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

/**
 * Transport/session boundary used by UpstreamGateway.
 *
 * <p>A connector owns transport resources and session mechanics. It transports
 * {@link UpstreamMessage} envelopes and treats their encoded payload as opaque.</p>
 */
public interface UpstreamConnector {

    /** Gateway callback installed once during runtime composition. */
    interface Listener {
        UpstreamGateway.ReceiveResult receive(UpstreamMessage message);

        void connected();

        void disconnected();

        void heartbeatTick();
    }

    /** Binds this connector to one gateway before the transport session starts. */
    void bind(Listener listener);

    /** Returns whether this connector currently has an operational transport session. */
    boolean isAvailable();

    /** Sends one routing envelope through the active transport session. */
    void send(UpstreamMessage message);
}
