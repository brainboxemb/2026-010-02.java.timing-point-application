package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Shared transport/session base for upstream connectors.
 *
 * <p>The base class owns the parts that are identical for every transport: one gateway input binding and the typed
 * CONNECTED/DISCONNECTED event ports. Concrete connectors own only transport-specific session state, framing and
 * outbound delivery.</p>
 *
 * <p>Inbound admission remains command-style because it returns {@link UpstreamGateway.ReceiveResult}. Subclasses pass
 * the resolved routing NodeId and unchanged encoded payload to {@link #receiveInput(NodeId, String)}.</p>
 */
public abstract class UpstreamConnector {
    private final Event<UpstreamConnector> connectedEvent = new Event<UpstreamConnector>();
    private final Event<UpstreamConnector> disconnectedEvent = new Event<UpstreamConnector>();

    /** Post-fact notification that this connector now has an operational transport session. */
    public final EventSource<UpstreamConnector> CONNECTED = connectedEvent;

    /** Post-fact notification that the current transport session has ended. */
    public final EventSource<UpstreamConnector> DISCONNECTED = disconnectedEvent;

    private UpstreamGateway.ConnectorPort input;

    /** Binds the command-style gateway input exactly once during composition. */
    public final synchronized void bindInput(UpstreamGateway.ConnectorPort newInput) {
        failIfInvalidArgument(newInput != null, "input must not be null");
        failIfIllegalState(input == null, "UpstreamConnector input is already bound");
        input = newInput;
    }

    /**
     * Hands one inbound encoded transport message to the bound gateway input.
     *
     * <p>NodeId remains routing context only; the payload is not interpreted by this base class.</p>
     */
    protected final UpstreamGateway.ReceiveResult receiveInput(NodeId nodeId, String encodedMessage) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(encodedMessage != null, "encodedMessage must not be null");

        UpstreamGateway.ConnectorPort currentInput;
        synchronized (this) {
            failIfIllegalState(input != null, "UpstreamConnector is not bound to an UpstreamGateway");
            currentInput = input;
        }
        return currentInput.receive(nodeId, encodedMessage);
    }

    /** Verifies that composition installed the gateway input before a transport session is opened. */
    protected final synchronized void requireBound() {
        failIfIllegalState(input != null, "UpstreamConnector is not bound to an UpstreamGateway");
    }

    /** Publishes one post-fact connection notification after the concrete transport became operational. */
    protected final void publishConnected() {
        connectedEvent.emit(this);
    }

    /** Publishes one post-fact disconnect notification after the concrete transport session ended. */
    protected final void publishDisconnected() {
        disconnectedEvent.emit(this);
    }

    /** Returns whether this connector currently has an operational transport session. */
    public abstract boolean isAvailable();

    /**
     * Sends one encoded message through the active transport session.
     *
     * <p>The routing NodeId is supplied separately because some transports need it to select an external destination.
     * It is not part of a generic message envelope and need not be re-read from the encoded protocol payload.</p>
     */
    public abstract void send(NodeId nodeId, String encodedMessage);
}
