package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Routes upstream messages between node streams and transport connectors.
 *
 * <p>The gateway owns only routing, queue admission and connector-to-node
 * lifecycle fan-out. It understands {@link NodeId} and {@link UpstreamMessage};
 * it does not know IF-06 message families or concrete UpstreamData subtypes.</p>
 *
 * <p>Each NodeUpstream has one independent inbound queue. The current runtime
 * binds one connector to each node, while one connector may serve several nodes.</p>
 */
public final class UpstreamGateway {
    static final int DEFAULT_INPUT_CAPACITY = 64;

    public enum ReceiveResult {
        ACCEPTED,
        UNKNOWN_NODE,
        INPUT_FULL,
        INPUT_REJECTED
    }

    private static final class NodeBinding {
        private final NodeId nodeId;
        private final UpstreamConnector connector;
        private final BooleanSupplier inputAvailable;
        private final Runnable connected;
        private final Runnable heartbeatTick;
        private final Deque<UpstreamMessage> input =
                new ArrayDeque<UpstreamMessage>();

        private NodeBinding(
                NodeId nodeId,
                UpstreamConnector connector,
                BooleanSupplier inputAvailable,
                Runnable connected,
                Runnable heartbeatTick) {
            this.nodeId = nodeId;
            this.connector = connector;
            this.inputAvailable = inputAvailable;
            this.connected = connected;
            this.heartbeatTick = heartbeatTick;
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
     * Connects one NodeUpstream mailbox to one transport connector.
     *
     * <p>Binding is composition-time work. The first node for a connector installs
     * the gateway listener; later nodes reuse that same connector session.</p>
     */
    public void bindNode(
            NodeId nodeId,
            UpstreamConnector connector,
            BooleanSupplier inputAvailable,
            Runnable connected,
            Runnable heartbeatTick) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(connector != null, "connector must not be null");
        failIfInvalidArgument(inputAvailable != null, "inputAvailable must not be null");
        failIfInvalidArgument(connected != null, "connected must not be null");
        failIfInvalidArgument(heartbeatTick != null, "heartbeatTick must not be null");

        boolean firstConnectorBinding;
        synchronized (this) {
            failIfInvalidArgument(!nodes.containsKey(nodeId),
                    "duplicate upstream node %s", nodeId.value());
            firstConnectorBinding = !connectors.containsKey(connector);
        }

        if (firstConnectorBinding) {
            connector.bind(new ConnectorListener(connector));
        }

        synchronized (this) {
            failIfInvalidArgument(!nodes.containsKey(nodeId),
                    "duplicate upstream node %s", nodeId.value());

            NodeBinding binding =
                    new NodeBinding(
                            nodeId,
                            connector,
                            inputAvailable,
                            connected,
                            heartbeatTick);
            nodes.put(nodeId, binding);

            List<NodeBinding> connectorBindings =
                    connectors.get(connector);
            if (connectorBindings == null) {
                connectorBindings = new ArrayList<NodeBinding>();
                connectors.put(connector, connectorBindings);
            }
            connectorBindings.add(binding);
        }
    }

    /** Returns whether this node's transport connector has an active session. */
    public boolean isAvailable(NodeId nodeId) {
        UpstreamConnector connector;
        synchronized (this) {
            connector = requireNode(nodeId).connector;
        }
        return connector.isAvailable();
    }

    /** Returns whether one inbound message is queued for this node. */
    public synchronized boolean hasMessage(NodeId nodeId) {
        return !requireNode(nodeId).input.isEmpty();
    }

    /** Removes and returns the next routed message for this node. */
    public synchronized UpstreamMessage read(NodeId nodeId) {
        UpstreamMessage message =
                requireNode(nodeId).input.poll();
        failIfIllegalState(message != null,
                "No upstream input is queued for node %s", nodeId.value());
        return message;
    }

    /** Routes one outbound envelope through the connector bound to its NodeId. */
    public void send(UpstreamMessage message) {
        failIfInvalidArgument(message != null, "message must not be null");

        UpstreamConnector connector;
        synchronized (this) {
            connector = requireNode(message.nodeId()).connector;
        }
        connector.send(message);
    }

    /**
     * Admits one connector-originated envelope to the addressed node queue.
     *
     * <p>If the node cannot retain the wake request, only the just-added envelope
     * is removed. Earlier queued messages remain untouched and ordered.</p>
     */
    private ReceiveResult receive(
            UpstreamConnector connector,
            UpstreamMessage message) {
        failIfInvalidArgument(message != null, "message must not be null");

        NodeBinding binding;
        synchronized (this) {
            binding = nodes.get(message.nodeId());
            if (binding == null
                    || binding.connector != connector) {
                return ReceiveResult.UNKNOWN_NODE;
            }
            if (binding.input.size() >= inputCapacity) {
                return ReceiveResult.INPUT_FULL;
            }
            binding.input.addLast(message);
        }

        if (binding.inputAvailable.getAsBoolean()) {
            return ReceiveResult.ACCEPTED;
        }

        synchronized (this) {
            binding.input.removeLastOccurrence(message);
        }
        return ReceiveResult.INPUT_REJECTED;
    }

    /** Clears unread node input belonging to one disconnected connector session. */
    private synchronized void disconnected(
            UpstreamConnector connector) {
        for (NodeBinding binding : requireConnector(connector)) {
            binding.input.clear();
        }
    }

    /** Notifies each NodeUpstream carried by a newly connected connector. */
    private void connected(
            UpstreamConnector connector) {
        for (NodeBinding binding : connectorBindings(connector)) {
            binding.connected.run();
        }
    }

    /** Forwards one connector heartbeat cadence tick to its NodeUpstream bindings. */
    private void heartbeatTick(
            UpstreamConnector connector) {
        for (NodeBinding binding : connectorBindings(connector)) {
            binding.heartbeatTick.run();
        }
    }

    /** Returns a stable snapshot so callbacks run outside the gateway monitor. */
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
                "Unknown upstream node %s", nodeId.value());
        return binding;
    }

    /** Connector callback adapter; no protocol semantics are interpreted here. */
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
