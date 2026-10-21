package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/**
 * Transport/session boundary used by {@link UpstreamGateway}.
 *
 * <p>The connector owns transport resources and session mechanics. Generic Messaging gives it a routing NodeId
 * separately from the encoded payload; the connector never interprets UpstreamData semantics.</p>
 *
 * <p>Connection changes are post-fact notifications and therefore use the local typed event framework. Inbound
 * transport data is different: admission returns {@link UpstreamGateway.ReceiveResult}, so it enters Messaging
 * through the explicit gateway input port rather than through an event.</p>
 */
public interface UpstreamConnector {

    /** Binds the command-style gateway input used for inbound transport messages. */
    void bindInput(UpstreamGateway.ConnectorPort input);

    /** Post-fact notification that this connector now has an operational transport session. */
    EventSource<UpstreamConnector> connectedEvent();

    /** Post-fact notification that the current transport session has ended. */
    EventSource<UpstreamConnector> disconnectedEvent();

    /** Returns whether this connector currently has an operational transport session. */
    boolean isAvailable();

    /**
     * Sends one encoded message through the active transport session.
     *
     * <p>The routing NodeId is supplied separately because some transports need it to select an external destination.
     * It is not part of a generic message envelope and need not be re-read from the encoded protocol payload.</p>
     */
    void send(NodeId nodeId, String encodedMessage);
}
