package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Engineering-API transport implementation of the system UpstreamGateway.
 *
 * <p>Every bound NodeId owns one inbound queue. All NodeUpstream instances share
 * one outbound queue. HTTP/WebSocket mechanics stay outside this class: inbound
 * presentation code submits already decoded UpstreamData through {@link #receive},
 * while the active engineering WebSocket peer is represented by {@link Output}.</p>
 *
 * <p>Receiving never executes protocol semantics. After enqueueing one node-scoped
 * message the connector only signals that node's NodeUpstream. NodeUpstream reads
 * and processes messages from its queue in protocol order.</p>
 */
public final class EngineerApiConnector implements UpstreamGateway {
    static final int DEFAULT_INPUT_CAPACITY = 64;
    static final int DEFAULT_OUTPUT_CAPACITY = 64;

    /** Non-blocking delivery boundary implemented by the existing IF-03 WebSocket adapter. */
    public interface Output {
        /**
         * Delivers one semantic message to the active engineering peer.
         *
         * @return true when the WebSocket adapter accepted the message
         */
        boolean send(UpstreamData message);
    }

    public enum ReceiveResult {
        ACCEPTED,
        NOT_CONNECTED,
        UNKNOWN_NODE,
        INPUT_FULL,
        INPUT_REJECTED
    }

    private static final class NodeInput {
        private final Deque<UpstreamData> messages =
                new ArrayDeque<UpstreamData>();
        private final BooleanSupplier inputAvailable;
        private final Runnable connected;

        private NodeInput(
                BooleanSupplier inputAvailable,
                Runnable connected) {
            this.inputAvailable = inputAvailable;
            this.connected = connected;
        }
    }

    private final int inputCapacity;
    private final int outputCapacity;
    private final Map<NodeId, NodeInput> inputs =
            new LinkedHashMap<NodeId, NodeInput>();
    private final Queue<UpstreamData> outputQueue =
            new ArrayDeque<UpstreamData>();

    private Output output;

    public EngineerApiConnector() {
        this(
                DEFAULT_INPUT_CAPACITY,
                DEFAULT_OUTPUT_CAPACITY);
    }

    EngineerApiConnector(
            int inputCapacity,
            int outputCapacity) {
        failIfInvalidArgument(inputCapacity > 0, "inputCapacity must be positive");
        failIfInvalidArgument(outputCapacity > 0, "outputCapacity must be positive");
        this.inputCapacity = inputCapacity;
        this.outputCapacity = outputCapacity;
    }

    /**
     * Binds one NodeUpstream to its connector input queue.
     *
     * <p>Binding is composition-time only and therefore must happen before an
     * engineering peer connects.</p>
     */
    public synchronized void bindNode(
            NodeId nodeId,
            BooleanSupplier inputAvailable,
            Runnable connected) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(inputAvailable != null, "inputAvailable must not be null");
        failIfInvalidArgument(connected != null, "connected must not be null");
        failIfIllegalState(output == null, "Cannot bind a node while connector is connected");
        failIfInvalidArgument(!inputs.containsKey(nodeId),
                "duplicate connector node %s", nodeId.value());

        inputs.put(
                nodeId,
                new NodeInput(
                        inputAvailable,
                        connected));
    }

    public synchronized boolean handles(
            NodeId nodeId) {
        return nodeId != null
                && inputs.containsKey(nodeId);
    }

    /**
     * Activates the engineering peer after its normal WebSocket status snapshot.
     *
     * <p>Only one peer may be connected. Successful connection invokes each
     * node's reconnect callback after connector state becomes available.</p>
     */
    public boolean connect(
            Output newOutput) {
        failIfInvalidArgument(newOutput != null, "output must not be null");

        Runnable[] connectedCallbacks;
        synchronized (this) {
            if (output != null) {
                return false;
            }
            outputQueue.clear();
            output = newOutput;
            connectedCallbacks =
                    new Runnable[inputs.size()];
            int index = 0;
            for (NodeInput input : inputs.values()) {
                connectedCallbacks[index++] =
                        input.connected;
            }
        }

        try {
            for (Runnable connected : connectedCallbacks) {
                connected.run();
            }
            return true;
        } catch (RuntimeException | Error failure) {
            disconnect(newOutput);
            throw failure;
        }
    }

    /**
     * Disconnects the supplied peer only when it is still the active peer.
     *
     * <p>Disconnect is a session boundary. Messages that have not yet been read
     * by NodeUpstream are discarded; reconnect synchronization determines what
     * the new peer must resend. A RACE_DATA message already handed to NodeUpstream
     * is no longer in this queue and may finish its node-local apply normally.</p>
     */
    public synchronized void disconnect(
            Output currentOutput) {
        if (currentOutput != null
                && output == currentOutput) {
            clearSession();
        }
    }

    /**
     * Queues one already decoded inbound message for its addressed node.
     *
     * <p>If NodeUpstream cannot retain the wake request, the just-added message
     * is removed again and INPUT_REJECTED is returned. Earlier queued messages
     * are never removed by this call.</p>
     */
    public ReceiveResult receive(
            UpstreamData message) {
        failIfInvalidArgument(message instanceof UpstreamData.NodeScoped,
                "Engineering upstream input must be node-scoped");

        UpstreamData.NodeScoped nodeScoped =
                (UpstreamData.NodeScoped) message;
        NodeInput input;
        synchronized (this) {
            if (output == null) {
                return ReceiveResult.NOT_CONNECTED;
            }

            input =
                    inputs.get(
                            nodeScoped.timingNodeId());
            if (input == null) {
                return ReceiveResult.UNKNOWN_NODE;
            }
            if (input.messages.size() >= inputCapacity) {
                return ReceiveResult.INPUT_FULL;
            }
            input.messages.add(message);
        }

        if (input.inputAvailable.getAsBoolean()) {
            return ReceiveResult.ACCEPTED;
        }

        synchronized (this) {
            input.messages.removeLastOccurrence(message);
        }
        return ReceiveResult.INPUT_REJECTED;
    }

    @Override
    public synchronized boolean isAvailable() {
        return output != null;
    }

    @Override
    public synchronized boolean hasMessage(
            NodeId nodeId) {
        NodeInput input =
                requireInput(nodeId);
        return !input.messages.isEmpty();
    }

    @Override
    public synchronized UpstreamData read(
            NodeId nodeId) {
        NodeInput input =
                requireInput(nodeId);
        UpstreamData message =
                input.messages.poll();
        failIfIllegalState(message != null,
                "No upstream input is queued for node %s", nodeId.value());
        return message;
    }

    /**
     * Adds one node-scoped message to the shared output queue and drains it to
     * the active engineering WebSocket peer.
     */
    @Override
    public synchronized void send(
            UpstreamData message) {
        failIfInvalidArgument(message instanceof UpstreamData.NodeScoped,
                "Outbound upstream data must be node-scoped");

        UpstreamData.NodeScoped nodeScoped =
                (UpstreamData.NodeScoped) message;
        failIfInvalidArgument(inputs.containsKey(nodeScoped.timingNodeId()),
                "Unknown outbound upstream node %s", nodeScoped.timingNodeId().value());
        failIfIllegalState(output != null, "Engineering upstream peer is not connected");

        if (outputQueue.size() >= outputCapacity) {
            throw new RejectedExecutionException(
                    "Engineering upstream output queue is full");
        }

        outputQueue.add(message);
        drainOutput();
    }

    private void drainOutput() {
        while (!outputQueue.isEmpty()) {
            UpstreamData message =
                    outputQueue.peek();
            final boolean accepted;
            try {
                accepted =
                        output.send(message);
            } catch (RuntimeException | Error failure) {
                clearSession();
                throw failure;
            }

            if (!accepted) {
                clearSession();
                throw new IllegalStateException(
                        "Engineering upstream peer rejected output");
            }
            outputQueue.remove();
        }
    }

    /** Clears all transport-session queues without changing node protocol state. */
    private void clearSession() {
        output = null;
        outputQueue.clear();
        for (NodeInput input : inputs.values()) {
            input.messages.clear();
        }
    }

    private NodeInput requireInput(
            NodeId nodeId) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        NodeInput input =
                inputs.get(nodeId);
        failIfInvalidArgument(input != null,
                "Unknown upstream node %s", nodeId.value());
        return input;
    }
}
