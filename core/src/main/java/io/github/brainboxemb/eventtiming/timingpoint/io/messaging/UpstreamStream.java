package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.ArrayDeque;
import java.util.Deque;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Node-scoped application port created by {@link UpstreamGateway}.
 *
 * <p>The NodeId-to-connector choice is resolved once when this stream is composed. Normal application-side reads,
 * writes and availability checks then use this fixed port directly; they do not route through the gateway by NodeId
 * again.</p>
 *
 * <p>The stream carries encoded protocol messages only. NodeUpstream owns semantic encoding and decoding. Its event
 * payload is the producing stream itself, following the local subject/observer convention without repeating NodeId
 * that is already fixed by composition.</p>
 */
public final class UpstreamStream {
    private final NodeId routingNodeId;
    private final UpstreamConnector connector;
    private final int inputCapacity;
    private final Deque<String> input = new ArrayDeque<String>();
    private final Event<UpstreamStream> connectedEvent = new Event<UpstreamStream>();
    private final Event<UpstreamStream> dataAvailableEvent = new Event<UpstreamStream>();

    /** Transport became available for this node-scoped application port. */
    public final EventSource<UpstreamStream> CONNECTED = connectedEvent;

    /** At least one encoded inbound message is queued for this application port. */
    public final EventSource<UpstreamStream> DATA_AVAILABLE = dataAvailableEvent;

    UpstreamStream(NodeId routingNodeId, UpstreamConnector connector, int inputCapacity) {
        failIfInvalidArgument(routingNodeId != null, "routingNodeId must not be null");
        failIfInvalidArgument(connector != null, "connector must not be null");
        failIfInvalidArgument(inputCapacity > 0, "inputCapacity must be positive");

        this.routingNodeId = routingNodeId;
        this.connector = connector;
        this.inputCapacity = inputCapacity;
    }

    /** Returns whether the transport session bound to this application port is currently usable. */
    public boolean isAvailable() {
        return connector.isAvailable();
    }

    /** Returns whether one inbound encoded message can be read without blocking. */
    public synchronized boolean hasMessage() {
        return !input.isEmpty();
    }

    /**
     * Consumes one inbound encoded message.
     *
     * <p>The queue belongs to this fixed application port, so no NodeId lookup is performed on the read path.</p>
     */
    public synchronized String read() {
        String encodedMessage = input.poll();
        failIfIllegalState(encodedMessage != null, "No upstream input is queued");
        return encodedMessage;
    }

    /**
     * Sends one encoded message through the connector fixed during composition.
     *
     * <p>The captured routing identity is passed directly to the connector for transports that need an external
     * destination key. The generic message itself remains only the encoded payload.</p>
     */
    public void send(String encodedMessage) {
        failIfInvalidArgument(encodedMessage != null, "encodedMessage must not be null");
        connector.send(routingNodeId, encodedMessage);
    }

    /**
     * Queues one message after UpstreamGateway has resolved the inbound NodeId to this application port.
     *
     * @return false when this port's bounded input queue is full
     */
    boolean offer(String encodedMessage) {
        failIfInvalidArgument(encodedMessage != null, "encodedMessage must not be null");

        synchronized (this) {
            if (input.size() >= inputCapacity) {
                return false;
            }
            input.addLast(encodedMessage);
        }

        dataAvailableEvent.emit(this);
        return true;
    }

    /** Publishes one post-fact connection notification for this application port. */
    void connected() {
        connectedEvent.emit(this);
    }

    /**
     * Drops unread input from the ended transport session.
     *
     * <p>Encoded messages are session input; retaining them across reconnect could apply stale peer data in the next
     * session.</p>
     */
    synchronized void disconnected() {
        input.clear();
    }
}
