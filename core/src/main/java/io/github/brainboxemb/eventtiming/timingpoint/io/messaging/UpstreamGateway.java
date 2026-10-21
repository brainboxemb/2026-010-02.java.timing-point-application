package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Resolves transport-side NodeId routing to fixed node-scoped application ports.
 *
 * <p>Routing happens only when inbound transport data arrives. Each {@link UpstreamStream} returned by
 * {@link #createApplicationPort(NodeId, UpstreamConnector)} already owns its connector and bounded input queue, so
 * normal application reads/writes do not perform another gateway map lookup.</p>
 *
 * <p>Connector session changes are post-fact events. Inbound data admission is a command with a result and therefore
 * uses {@link ConnectorPort} rather than pretending that queue acceptance is an event.</p>
 */
public final class UpstreamGateway {
    static final int DEFAULT_INPUT_CAPACITY = 64;

    /** Result of admitting one transport message to its resolved application port. */
    public enum ReceiveResult {
        ACCEPTED,
        UNKNOWN_NODE,
        INPUT_FULL
    }

    /**
     * Command-style input port given to one transport connector.
     *
     * <p>The connector resolves transport framing only. This gateway port owns NodeId routing and bounded application
     * admission.</p>
     */
    public interface ConnectorPort {
        ReceiveResult receive(NodeId nodeId, String encodedMessage);
    }

    /** Connector connection events are wired through the same typed local-event mechanism as other components. */
    private static final EventInput<UpstreamGateway, UpstreamConnector> CONNECTED =
            EventInput.of((gateway, source) -> source.subscribe(gateway::onConnected));

    /** Connector disconnect events clear only the application ports that belong to that connector. */
    private static final EventInput<UpstreamGateway, UpstreamConnector> DISCONNECTED =
            EventInput.of((gateway, source) -> source.subscribe(gateway::onDisconnected));

    private static final class NodeBinding {
        private final UpstreamConnector connector;
        private final UpstreamStream stream;

        private NodeBinding(UpstreamConnector connector, UpstreamStream stream) {
            this.connector = connector;
            this.stream = stream;
        }
    }

    private final int inputCapacity;
    private final Map<NodeId, NodeBinding> nodes = new LinkedHashMap<NodeId, NodeBinding>();
    private final Map<UpstreamConnector, List<UpstreamStream>> connectors =
            new LinkedHashMap<UpstreamConnector, List<UpstreamStream>>();

    public UpstreamGateway() {
        this(DEFAULT_INPUT_CAPACITY);
    }

    UpstreamGateway(int inputCapacity) {
        failIfInvalidArgument(inputCapacity > 0, "inputCapacity must be positive");
        this.inputCapacity = inputCapacity;
    }

    /**
     * Creates one fixed node-scoped application port.
     *
     * <p>The NodeId is consumed here to establish routing. The returned stream subsequently talks directly to its
     * bound connector and queue without presenting NodeId again to the gateway.</p>
     */
    public synchronized UpstreamStream createApplicationPort(NodeId nodeId, UpstreamConnector connector) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(connector != null, "connector must not be null");
        failIfInvalidArgument(!nodes.containsKey(nodeId), "duplicate upstream node %s", nodeId.value());

        List<UpstreamStream> connectorStreams = connectors.get(connector);
        if (connectorStreams == null) {
            connector.bindInput((messageNodeId, encodedMessage) -> receive(connector, messageNodeId, encodedMessage));
            CONNECTED.bind(this, connector.CONNECTED);
            DISCONNECTED.bind(this, connector.DISCONNECTED);

            connectorStreams = new ArrayList<UpstreamStream>();
            connectors.put(connector, connectorStreams);
        }

        UpstreamStream stream = new UpstreamStream(nodeId, connector, inputCapacity);
        nodes.put(nodeId, new NodeBinding(connector, stream));
        connectorStreams.add(stream);
        return stream;
    }

    /**
     * Resolves one transport message to its fixed application port.
     *
     * <p>The source connector is checked as part of routing so a connector cannot inject a NodeId that belongs to a
     * different configured transport binding.</p>
     */
    private ReceiveResult receive(UpstreamConnector connector, NodeId nodeId, String encodedMessage) {
        failIfInvalidArgument(connector != null, "connector must not be null");
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(encodedMessage != null, "encodedMessage must not be null");

        UpstreamStream stream;
        synchronized (this) {
            NodeBinding binding = nodes.get(nodeId);
            if (binding == null || binding.connector != connector) {
                return ReceiveResult.UNKNOWN_NODE;
            }
            stream = binding.stream;
        }

        return stream.offer(encodedMessage) ? ReceiveResult.ACCEPTED : ReceiveResult.INPUT_FULL;
    }

    /** Fans one connector connection fact out to the application ports already bound to that connector. */
    private void onConnected(UpstreamConnector connector) {
        for (UpstreamStream stream : connectorStreams(connector)) {
            stream.connected();
        }
    }

    /** Clears unread session data for all application ports bound to the disconnected connector. */
    private void onDisconnected(UpstreamConnector connector) {
        for (UpstreamStream stream : connectorStreams(connector)) {
            stream.disconnected();
        }
    }

    /** Takes a stable composition-built snapshot before invoking application-port event handlers. */
    private synchronized List<UpstreamStream> connectorStreams(UpstreamConnector connector) {
        List<UpstreamStream> streams = connectors.get(connector);
        failIfIllegalState(streams != null, "Connector is not bound to this UpstreamGateway");
        return new ArrayList<UpstreamStream>(streams);
    }
}
