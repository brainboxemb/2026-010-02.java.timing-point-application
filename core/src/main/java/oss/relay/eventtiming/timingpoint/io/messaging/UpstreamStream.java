package oss.relay.eventtiming.timingpoint.io.messaging;

import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingpoint.platform.events.Event;
import oss.relay.eventtiming.timingpoint.platform.events.EventSource;

import java.util.ArrayDeque;
import java.util.Deque;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Fixed node-scoped application port between NodeUpstream and one transport connector.
 *
 * <p>The routing NodeId and connector are construction-time dependencies. Runtime reads and
 * writes therefore never perform another gateway lookup.</p>
 */
public final class UpstreamStream {
    private static final int INPUT_CAPACITY = 64;

    private final NodeId routingNodeId;
    private final UpstreamConnector connector;
    private final Deque<String> input = new ArrayDeque<String>();
    private final Event<UpstreamStream> connectedEvent = new Event<UpstreamStream>();
    private final Event<UpstreamStream> disconnectedEvent = new Event<UpstreamStream>();
    private final Event<UpstreamStream> dataAvailableEvent = new Event<UpstreamStream>();

    /** Sender identity is the already composed node stream; NodeId is not repeated in the event. */
    public final EventSource<UpstreamStream> CONNECTED = connectedEvent;

    /** Session input has already been cleared when this event is delivered. */
    public final EventSource<UpstreamStream> DISCONNECTED = disconnectedEvent;

    /** This is a wake signal; the bounded input queue remains the authoritative pending-data state. */
    public final EventSource<UpstreamStream> DATA_AVAILABLE = dataAvailableEvent;

    /** Each node receives an independent bounded input queue. */
    public UpstreamStream(NodeId routingNodeId, UpstreamConnector connector) {

        failIfInvalidArgument(routingNodeId != null, "routingNodeId must not be null");
        failIfInvalidArgument(connector != null, "connector must not be null");

        this.routingNodeId = routingNodeId;
        this.connector = connector;
    }

    /** Availability follows the bound connector; the stream has no independent session state. */
    public boolean isAvailable() {
        return connector.isAvailable();
    }

    /** Queue state is checked without blocking; event delivery is only a wake mechanism. */
    public synchronized boolean hasMessage() {
        return !input.isEmpty();
    }

    /** Reading is non-blocking and requires queued input to be present. */
    public synchronized String read() {

        String encodedMessage = input.poll();
        failIfIllegalState(encodedMessage != null, "No upstream input is queued");
        return encodedMessage;
    }

    /** Outbound routing uses the NodeId captured once during composition. */
    public void send(String encodedMessage) {

        failIfInvalidArgument(encodedMessage != null, "encodedMessage must not be null");
        connector.send(routingNodeId, encodedMessage);
    }

    /**
     * The bounded queue prevents one node's inbound burst from becoming unbounded retained state.
     *
     * @return false when this stream has reached its configured input capacity
     */
    boolean offer(String encodedMessage) {

        failIfInvalidArgument(encodedMessage != null, "encodedMessage must not be null");

        synchronized (this) {
            if (input.size() >= INPUT_CAPACITY) {
                return false;
            }
            input.addLast(encodedMessage);
        }

        dataAvailableEvent.emit(this);
        return true;
    }

    /** Connector availability is fanned out only to streams fixed to that connector. */
    void connected() {
        connectedEvent.emit(this);
    }

    /** Unread data belongs to the ended transport session and must not survive reconnect. */
    void disconnected() {
        synchronized (this) {
            input.clear();
        }
        disconnectedEvent.emit(this);
    }

    /** Gateway construction rejects a routing table that pairs this stream with another binding. */
    boolean isBoundTo(NodeId nodeId, UpstreamConnector expectedConnector) {
        return routingNodeId.equals(nodeId) && connector == expectedConnector;
    }
}
