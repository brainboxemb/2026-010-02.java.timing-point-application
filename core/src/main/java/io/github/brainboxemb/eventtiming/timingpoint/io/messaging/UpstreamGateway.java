package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

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
 * Messaging-owned router between node-scoped UpstreamStreams and transport connectors.
 *
 * <p>Each bound NodeUpstream has one independent inbound queue. The current
 * implementation binds exactly one connector to each node. A connector may serve
 * several nodes, so one physical transport session can still multiplex NodeIds.
 * Supporting several simultaneous connectors for one node requires an explicit
 * outbound/failover policy and is deliberately not guessed here.</p>
 *
 * <p>The gateway owns semantic NodeId routing and queue admission only. Connector
 * session resources and wire I/O remain in UpstreamConnector; RACE_DATA,
 * TIMING_DATA and PING semantics remain in NodeUpstream.</p>
 */
public final class UpstreamGateway {
    static final int DEFAULT_INPUT_CAPACITY = 64;

    public enum ReceiveResult {
        ACCEPTED,
        UNKNOWN_NODE,
        INPUT_FULL,
        INPUT_REJECTED
    }

    private static final class QueuedInput {
        private final UpstreamData message;

        private QueuedInput(UpstreamData message) {
            this.message = message;
        }
    }

    private static final class NodeBinding {
        private final UpstreamConnector connector;
        private final BooleanSupplier inputAvailable;
        private final Runnable connected;
        private final Runnable heartbeatTick;
        private final Deque<QueuedInput> input =
                new ArrayDeque<QueuedInput>();

        private NodeBinding(
                UpstreamConnector connector,
                BooleanSupplier inputAvailable,
                Runnable connected,
                Runnable heartbeatTick) {
            this.connector = connector;
            this.inputAvailable = inputAvailable;
            this.connected = connected;
            this.heartbeatTick = heartbeatTick;
        }
    }

    private final int inputCapacity;
    private final Map<NodeId, NodeBinding> nodes =
            new LinkedHashMap<NodeId, NodeBinding>();
    private final Map<UpstreamConnector, List<NodeId>> connectorNodes =
            new LinkedHashMap<UpstreamConnector, List<NodeId>>();

    public UpstreamGateway() {
        this(DEFAULT_INPUT_CAPACITY);
    }

    UpstreamGateway(int inputCapacity) {
        failIfInvalidArgument(inputCapacity > 0, "inputCapacity must be positive");
        this.inputCapacity = inputCapacity;
    }

    /**
     * Binds one NodeUpstream mailbox to one connector.
     *
     * <p>Binding is composition-time only. A connector is attached to this
     * gateway on its first node binding and may then serve additional nodes.</p>
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

        boolean bindConnector;
        synchronized (this) {
            failIfInvalidArgument(!nodes.containsKey(nodeId),
                    "duplicate upstream node %s", nodeId.value());
            bindConnector = !connectorNodes.containsKey(connector);
        }

        if (bindConnector) {
            connector.bind(new ConnectorListener(connector));
        }

        synchronized (this) {
            failIfInvalidArgument(!nodes.containsKey(nodeId),
                    "duplicate upstream node %s", nodeId.value());

            List<NodeId> boundNodes =
                    connectorNodes.get(connector);
            if (boundNodes == null) {
                boundNodes = new ArrayList<NodeId>();
                connectorNodes.put(connector, boundNodes);
            }

            nodes.put(
                    nodeId,
                    new NodeBinding(
                            connector,
                            inputAvailable,
                            connected,
                            heartbeatTick));
            boundNodes.add(nodeId);
        }
    }

    /** Returns whether this node's bound connector currently has an active session. */
    public boolean isAvailable(NodeId nodeId) {
        UpstreamConnector connector;
        synchronized (this) {
            connector = requireNode(nodeId).connector;
        }
        return connector.isAvailable();
    }

    /** Returns true when at least one configured node has an available connector. */
    public boolean isAvailable() {
        List<UpstreamConnector> connectors;
        synchronized (this) {
            connectors =
                    new ArrayList<UpstreamConnector>(
                            connectorNodes.keySet());
        }
        for (UpstreamConnector connector : connectors) {
            if (connector.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean hasMessage(NodeId nodeId) {
        return !requireNode(nodeId).input.isEmpty();
    }

    public synchronized UpstreamData read(NodeId nodeId) {
        QueuedInput queued =
                requireNode(nodeId).input.poll();
        failIfIllegalState(queued != null,
                "No upstream input is queued for node %s", nodeId.value());
        return queued.message;
    }

    /** Routes one node-scoped outbound message through that node's configured connector. */
    public void send(UpstreamData message) {
        failIfInvalidArgument(message instanceof UpstreamData.NodeScoped,
                "Outbound upstream data must be node-scoped");

        NodeId nodeId =
                ((UpstreamData.NodeScoped) message)
                        .timingNodeId();
        UpstreamConnector connector;
        synchronized (this) {
            connector = requireNode(nodeId).connector;
        }
        connector.send(message);
    }

    private ReceiveResult receive(
            UpstreamConnector connector,
            UpstreamData message) {
        failIfInvalidArgument(message instanceof UpstreamData.NodeScoped,
                "Inbound upstream data must be node-scoped");

        NodeId nodeId =
                ((UpstreamData.NodeScoped) message)
                        .timingNodeId();
        NodeBinding binding;
        QueuedInput queued =
                new QueuedInput(message);

        synchronized (this) {
            binding = nodes.get(nodeId);
            if (binding == null
                    || binding.connector != connector) {
                return ReceiveResult.UNKNOWN_NODE;
            }
            if (binding.input.size() >= inputCapacity) {
                return ReceiveResult.INPUT_FULL;
            }
            binding.input.addLast(queued);
        }

        if (binding.inputAvailable.getAsBoolean()) {
            return ReceiveResult.ACCEPTED;
        }

        synchronized (this) {
            binding.input.removeLastOccurrence(queued);
        }
        return ReceiveResult.INPUT_REJECTED;
    }

    /** Drops unread input owned by a disconnected connector session. */
    private void disconnected(UpstreamConnector connector) {
        synchronized (this) {
            for (NodeId nodeId : connectorNodeIds(connector)) {
                NodeBinding binding = nodes.get(nodeId);
                if (binding != null) {
                    binding.input.clear();
                }
            }
        }
    }

    /** Requests source-head reconciliation for every node carried by this connector. */
    private void connected(UpstreamConnector connector) {
        for (NodeBinding binding : connectorBindings(connector)) {
            binding.connected.run();
        }
    }

    /** Forwards connector cadence to the node-scoped heartbeat protocol owners. */
    private void heartbeatTick(UpstreamConnector connector) {
        for (NodeBinding binding : connectorBindings(connector)) {
            binding.heartbeatTick.run();
        }
    }

    private synchronized List<NodeBinding> connectorBindings(
            UpstreamConnector connector) {
        List<NodeBinding> bindings =
                new ArrayList<NodeBinding>();
        for (NodeId nodeId : connectorNodeIds(connector)) {
            NodeBinding binding = nodes.get(nodeId);
            if (binding != null) {
                bindings.add(binding);
            }
        }
        return bindings;
    }

    private List<NodeId> connectorNodeIds(
            UpstreamConnector connector) {
        List<NodeId> nodeIds =
                connectorNodes.get(connector);
        failIfIllegalState(nodeIds != null,
                "Connector is not bound to this UpstreamGateway");
        return nodeIds;
    }

    private NodeBinding requireNode(NodeId nodeId) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        NodeBinding binding = nodes.get(nodeId);
        failIfInvalidArgument(binding != null,
                "Unknown upstream node %s", nodeId.value());
        return binding;
    }

    private final class ConnectorListener
            implements UpstreamConnector.Listener {
        private final UpstreamConnector connector;

        private ConnectorListener(UpstreamConnector connector) {
            this.connector = connector;
        }

        @Override
        public ReceiveResult receive(UpstreamData message) {
            return UpstreamGateway.this.receive(connector, message);
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
