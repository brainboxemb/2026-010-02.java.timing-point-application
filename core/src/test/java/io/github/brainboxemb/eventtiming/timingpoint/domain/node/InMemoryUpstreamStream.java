package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamConnector;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGateway;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/** Test fixture around the real gateway-created UpstreamStream application port. */
final class InMemoryUpstreamStream {
    private static final long SEND_WAIT_MILLIS = 1000L;

    private NodeId nodeId;
    private final UpstreamDataCodec codec = new DefaultUpstreamDataCodec(new DefaultTimingDataCodec());
    private final TestConnector connector = new TestConnector();
    private UpstreamStream stream;

    UpstreamStream bind(NodeId requestedNodeId) {
        if (stream == null) {
            nodeId = requestedNodeId;
            UpstreamGateway gateway = new UpstreamGateway();
            stream = gateway.createApplicationPort(nodeId, connector);
        } else if (!nodeId.equals(requestedNodeId)) {
            throw new IllegalStateException("test stream is already bound to another node");
        }
        return stream;
    }

    UpstreamStream stream() {
        if (stream == null) {
            throw new IllegalStateException("test stream is not bound");
        }
        return stream;
    }

    void connect() {
        connector.connect();
    }

    void disconnect() {
        connector.disconnect();
    }

    void setAvailable(boolean available) {
        if (available) {
            connector.connect();
        } else {
            connector.disconnect();
        }
    }

    /** Encodes semantic test input before handing it to the connector-side gateway port. */
    void receive(UpstreamData data) {
        if (!(data instanceof UpstreamData.NodeScoped)) {
            throw new IllegalArgumentException("test UpstreamData must be node-scoped");
        }

        UpstreamData.NodeScoped nodeScoped = (UpstreamData.NodeScoped) data;
        if (!nodeId.equals(nodeScoped.timingNodeId())) {
            throw new IllegalArgumentException("test UpstreamData belongs to another node");
        }

        try {
            String encoded = new String(codec.encode(data), StandardCharsets.UTF_8);
            UpstreamGateway.ReceiveResult result = connector.receive(nodeId, encoded);
            if (result != UpstreamGateway.ReceiveResult.ACCEPTED) {
                throw new IllegalStateException("test upstream input was not accepted: " + result);
            }
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalStateException("could not encode test UpstreamData", ex);
        }
    }

    /** Decodes one connector-side outbound value back to semantic test data. */
    UpstreamData takeSent() {
        SentMessage message = connector.takeSent();
        try {
            return codec.decode(message.payload.getBytes(StandardCharsets.UTF_8));
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalStateException("could not decode test UpstreamData", ex);
        }
    }

    int sentCount() {
        return connector.sentCount();
    }

    void onNextSend(Runnable action) {
        connector.onNextSend(action);
    }

    private static final class SentMessage {
        private final NodeId nodeId;
        private final String payload;

        private SentMessage(NodeId nodeId, String payload) {
            this.nodeId = nodeId;
            this.payload = payload;
        }
    }

    /** Minimal transport double that keeps the real event/input-port wiring intact. */
    private static final class TestConnector implements UpstreamConnector {
        private final Queue<SentMessage> outbound = new ArrayDeque<SentMessage>();
        private final Event<UpstreamConnector> connectedEvent = new Event<UpstreamConnector>();
        private final Event<UpstreamConnector> disconnectedEvent = new Event<UpstreamConnector>();

        private UpstreamGateway.ConnectorPort input;
        private boolean available = true;
        private Runnable onNextSend;

        @Override
        public synchronized void bindInput(UpstreamGateway.ConnectorPort newInput) {
            if (input != null) {
                throw new IllegalStateException("test connector input already bound");
            }
            input = newInput;
        }

        @Override
        public EventSource<UpstreamConnector> connectedEvent() {
            return connectedEvent;
        }

        @Override
        public EventSource<UpstreamConnector> disconnectedEvent() {
            return disconnectedEvent;
        }

        @Override
        public synchronized boolean isAvailable() {
            return available;
        }

        @Override
        public void send(NodeId nodeId, String encodedMessage) {
            Runnable action;
            synchronized (this) {
                if (!available) {
                    throw new IllegalStateException("test connector is not connected");
                }
                outbound.add(new SentMessage(nodeId, encodedMessage));
                action = onNextSend;
                onNextSend = null;
                notifyAll();
            }
            if (action != null) {
                action.run();
            }
        }

        private void connect() {
            synchronized (this) {
                if (available) {
                    return;
                }
                available = true;
            }
            connectedEvent.emit(this);
        }

        private void disconnect() {
            synchronized (this) {
                if (!available) {
                    return;
                }
                available = false;
            }
            disconnectedEvent.emit(this);
        }

        private UpstreamGateway.ReceiveResult receive(NodeId nodeId, String encodedMessage) {
            synchronized (this) {
                if (!available) {
                    throw new IllegalStateException("test connector is not connected");
                }
            }
            return input.receive(nodeId, encodedMessage);
        }

        private SentMessage takeSent() {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEND_WAIT_MILLIS);

            synchronized (this) {
                while (outbound.isEmpty()) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0L) {
                        throw new IllegalStateException("no outbound upstream message available");
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted while waiting for outbound upstream message", ex);
                    }
                }
                return outbound.remove();
            }
        }

        private synchronized int sentCount() {
            return outbound.size();
        }

        private synchronized void onNextSend(Runnable action) {
            if (action == null) {
                throw new IllegalArgumentException("action must not be null");
            }
            onNextSend = action;
        }
    }
}
