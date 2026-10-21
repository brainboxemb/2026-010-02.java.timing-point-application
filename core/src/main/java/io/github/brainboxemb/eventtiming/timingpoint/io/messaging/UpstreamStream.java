package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * One node-scoped bidirectional view over a shared UpstreamGateway.
 *
 * <p>The stream carries encoded messages only. Protocol encoding and decoding
 * belong to NodeUpstream. NodeId routing is added and removed here.</p>
 */
public final class UpstreamStream {
    private final NodeId nodeId;
    private final UpstreamGateway gateway;
    private final Event<NodeId> connectedEvent = new Event<NodeId>();
    private final Event<NodeId> dataAvailableEvent = new Event<NodeId>();
    private final Event<NodeId> heartbeatTickEvent = new Event<NodeId>();

    /** Transport became available for this node-scoped stream. */
    public final EventSource<NodeId> CONNECTED = connectedEvent;

    /** At least one encoded inbound message is queued for this stream. */
    public final EventSource<NodeId> DATA_AVAILABLE = dataAvailableEvent;

    /** Connector heartbeat cadence reached its next tick. */
    public final EventSource<NodeId> HEARTBEAT_TICK = heartbeatTickEvent;

    UpstreamStream(
            NodeId nodeId,
            UpstreamGateway gateway) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(gateway != null, "gateway must not be null");
        this.nodeId = nodeId;
        this.gateway = gateway;
    }

    /** Returns whether the transport session used by this stream is available. */
    public boolean isAvailable() {
        return gateway.isAvailable(nodeId);
    }

    /** Returns whether one inbound encoded message can be read without blocking. */
    public boolean hasMessage() {
        return gateway.hasMessage(nodeId);
    }

    /** Consumes one inbound encoded message. */
    public String read() {
        return gateway.read(nodeId).payload();
    }

    /** Sends one encoded message from this node through the shared gateway. */
    public void send(
            String encodedMessage) {
        failIfInvalidArgument(encodedMessage != null,
                "encodedMessage must not be null");
        gateway.send(
                new UpstreamMessage(
                        nodeId,
                        encodedMessage));
    }

    void connected() {
        connectedEvent.emit(nodeId);
    }

    void dataAvailable() {
        dataAvailableEvent.emit(nodeId);
    }

    void heartbeatTick() {
        heartbeatTickEvent.emit(nodeId);
    }
}
