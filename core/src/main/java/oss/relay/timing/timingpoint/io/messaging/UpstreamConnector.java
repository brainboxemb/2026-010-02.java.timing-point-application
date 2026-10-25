package oss.relay.timing.timingpoint.io.messaging;

import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingpoint.platform.events.Event;
import oss.relay.timing.timingpoint.platform.events.EventSource;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfIllegalState;
import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Shared transport/session base for upstream connectors.
 *
 * <p>Every connector is bound once to the gateway that owns inbound routing. Concrete
 * connectors retain only transport-specific session, framing and outbound-delivery state.</p>
 */
public abstract class UpstreamConnector {
    private final Event<UpstreamConnector> connectedEvent = new Event<UpstreamConnector>();
    private final Event<UpstreamConnector> disconnectedEvent = new Event<UpstreamConnector>();

    /** Subscribers may send immediately when this event is delivered. */
    public final EventSource<UpstreamConnector> CONNECTED = connectedEvent;

    /** Session state has already been cleared when this event is delivered. */
    public final EventSource<UpstreamConnector> DISCONNECTED = disconnectedEvent;

    private Input input;

    /** The gateway input is a fixed composition dependency and cannot be replaced. */
    final synchronized void bindInput(Input newInput) {

        failIfInvalidArgument(newInput != null, "input must not be null");
        failIfIllegalState(input == null, "UpstreamConnector input is already bound");
        input = newInput;
    }

    /** NodeId remains routing context here; semantic IF-06 decoding happens above Messaging. */
    protected final UpstreamGateway.ReceiveResult receiveInput(NodeId nodeId, String encodedMessage) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(encodedMessage != null, "encodedMessage must not be null");

        Input currentInput;
        synchronized (this) {
            failIfIllegalState(input != null, "UpstreamConnector is not bound to an UpstreamGateway");
            currentInput = input;
        }
        return currentInput.receive(nodeId, encodedMessage);
    }

    /** A transport session may open only after the connector has its fixed gateway input. */
    protected final synchronized void requireBound() {
        failIfIllegalState(input != null, "UpstreamConnector is not bound to an UpstreamGateway");
    }

    /** Connection is published only after the transport is ready for outbound traffic. */
    protected final void publishConnected() {
        connectedEvent.emit(this);
    }

    /** Disconnect is published after the transport no longer accepts session traffic. */
    protected final void publishDisconnected() {
        disconnectedEvent.emit(this);
    }

    /** Availability is the concrete transport session state, not gateway or stream state. */
    public abstract boolean isAvailable();

    /** NodeId stays separate because a transport may need it as destination-routing context. */
    public abstract void send(NodeId nodeId, String encodedMessage);

    interface Input {
        UpstreamGateway.ReceiveResult receive(NodeId nodeId, String encodedMessage);
    }
}
