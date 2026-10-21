package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Routes encoded upstream messages between node streams and transport connectors.
 *
 * <p>The gateway owns routing, bounded per-node input queues and connector-to-stream
 * lifecycle fan-out. Message payloads are opaque.</p>
 */
public final class UpstreamGateway {
    static final int DEFAULT_INPUT_CAPACITY = 64;

    public enum ReceiveResult {
        ACCEPTED,
        UNKNOWN_NODE,
        INPUT_FULL
    }

    private static final class NodeBinding {
        private final UpstreamConnector connector;
        private final UpstreamStream stream;
        private final Deque<UpstreamMessage> input =
                new ArrayDeque<UpstreamMessage>();

        private NodeBinding(
                UpstreamConnector connector,
                UpstreamStream stream) {
            this.connector = connector;
            this.stream = stream;
        }
    }

    private final int inputCapacity;
    private final Map<NodeId, NodeBinding> nodes =
            new LinkedHashMap<NodeId, NodeBinding>();
    private final Map<UpstreamConnector, List<NodeBinding>> connectors =
            new LinkedHashMap<UpstreamConnector, List<NodeBinding>>();

    public UpstreamGateway() {
        this(DEFAULT_INPUT_CAPACITY);
    }

    UpstreamGateway(int inputCapacity) {
        failIfInvalidArgument(inputCapacity > 0, "inputCapacity must be positive");
        this.inputCapacity = inputCapacity;
    }

    /**
     * Creates and binds one node-scoped stream to a connector.
     *
     * <p>The first stream for a connector installs the gateway listener; later
     * streams reuse that same transport session.</p>
     */
    public synchronized UpstreamStream createStream(
            NodeId nodeId,
            UpstreamConnector connector) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(connector != null, "connector must not be null");
        failIfInvalidArgument(!nodes.containsKey(nodeId),
                "duplicate upstream node %s", nodeId.value());

        List<NodeBinding> connectorBindings =
                connectors.get(connector);
        if (connectorBindings == null) {
            connector.bind(new ConnectorListener(connector));
            connectorBindings = new ArrayList<NodeBinding>();
            connectors.put(connector, connectorBindings);
        }

        UpstreamStream stream =
                new UpstreamStream(
                        nodeId,
                        this);
        NodeBinding binding =
                new NodeBinding(
                        connector,
                        stream);
        nodes.put(nodeId, binding);
        connectorBindings.add(binding);
        return stream;
    }

    boolean isAvailable(
            NodeId nodeId) {
        UpstreamConnector connector;
        synchronized (this) {
            connector = requireNode(nodeId).connector;
        }
        return connector.isAvailable();
    }

    synchronized boolean hasMessage(
            NodeId nodeId) {
        return !requireNode(nodeId).input.isEmpty();
    }

    synchronized UpstreamMessage read(
            NodeId nodeId) {
        UpstreamMessage message =
                requireNode(nodeId).input.poll();
        failIfIllegalState(message != null,
                "No upstream input is queued for node %s",
                nodeId.value());
        return message;
    }

    void send(
            UpstreamMessage message) {
        failIfInvalidArgument(message != null, "message must not be null");

        UpstreamConnector connector;
        synchronized (this) {
            connector = requireNode(message.nodeId()).connector;
        }
        connector.send(message);
    }

    private ReceiveResult receive(
            UpstreamConnector connector,
            UpstreamMessage message) {
        failIfInvalidArgument(message != null, "message must not be null");

        UpstreamStream stream;
        synchronized (this) {
            NodeBinding binding =
                    nodes.get(message.nodeId());
            if (binding == null
                    || binding.connector != connector) {
                return ReceiveResult.UNKNOWN_NODE;
            }
            if (binding.input.size() >= inputCapacity) {
                return ReceiveResult.INPUT_FULL;
            }
            binding.input.addLast(message);
            stream = binding.stream;
        }

        stream.dataAvailable();
        return ReceiveResult.ACCEPTED;
    }

    private synchronized void disconnected(
            UpstreamConnector connector) {
        for (NodeBinding binding : requireConnector(connector)) {
            binding.input.clear();
        }
    }

    private void connected(
            UpstreamConnector connector) {
        for (NodeBinding binding : connectorBindings(connector)) {
            binding.stream.connected();
        }
    }

    private void heartbeatTick(
            UpstreamConnector connector) {
        for (NodeBinding binding : connectorBindings(connector)) {
            binding.stream.heartbeatTick();
        }
    }

    private synchronized List<NodeBinding> connectorBindings(
            UpstreamConnector connector) {
        return new ArrayList<NodeBinding>(
                requireConnector(connector));
    }

    private List<NodeBinding> requireConnector(
            UpstreamConnector connector) {
        List<NodeBinding> bindings =
                connectors.get(connector);
        failIfIllegalState(bindings != null,
                "Connector is not bound to this UpstreamGateway");
        return bindings;
    }

    private NodeBinding requireNode(
            NodeId nodeId) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        NodeBinding binding = nodes.get(nodeId);
        failIfInvalidArgument(binding != null,
                "Unknown upstream node %s",
                nodeId.value());
        return binding;
    }

    private final class ConnectorListener
            implements UpstreamConnector.Listener {
        private final UpstreamConnector connector;

        private ConnectorListener(
                UpstreamConnector connector) {
            this.connector = connector;
        }

        @Override
        public ReceiveResult receive(
                UpstreamMessage message) {
            return UpstreamGateway.this.receive(
                    connector,
                    message);
        }

        @Override
        public void connected() {
            UpstreamGateway.this.connected(connector);
        }

        @Override
        public void disconnected() {
            UpstreamGateway.this.disconnected(connector);
        }

        @Override
        public void heartbeatTick() {
            UpstreamGateway.this.heartbeatTick(connector);
        }
    }
}
