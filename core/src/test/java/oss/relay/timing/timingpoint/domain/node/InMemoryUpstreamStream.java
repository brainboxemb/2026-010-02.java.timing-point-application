package oss.relay.timing.timingpoint.domain.node;

import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.defaultprofile.DefaultTimingDataCodec;
import oss.relay.timing.timingpoint.io.messaging.UpstreamConnector;
import oss.relay.timing.timingpoint.io.messaging.UpstreamGateway;
import oss.relay.timing.timingpoint.io.messaging.UpstreamStream;
import oss.relay.timing.upstreamdata.UpstreamData;
import oss.relay.timing.upstreamdata.UpstreamDataCodec;
import oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamDataCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/** Test fixture around a real composed UpstreamStream and UpstreamGateway. */
final class InMemoryUpstreamStream {
    private static final long SEND_WAIT_MILLIS = 1000L;

    private NodeId nodeId;
    private final UpstreamDataCodec codec = new DefaultUpstreamDataCodec(new DefaultTimingDataCodec());
    private final TestConnector connector = new TestConnector();
    private UpstreamGateway gateway;
    private UpstreamStream stream;

    UpstreamStream bind(NodeId requestedNodeId) {
        if (stream == null) {
            nodeId = requestedNodeId;
            stream = new UpstreamStream(nodeId, connector);
            gateway = new UpstreamGateway(connector, Collections.singletonMap(nodeId, stream));
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
        if (!nodeId.equals(data.timingNodeId())) {
            throw new IllegalArgumentException("test UpstreamData belongs to another node");
        }

        try {
            String encoded = new String(codec.encode(data), StandardCharsets.UTF_8);
            UpstreamGateway.ReceiveResult result = connector.receiveTestInput(nodeId, encoded);
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

    /** Minimal transport double that keeps the real shared connector/event wiring intact. */
    private static final class TestConnector extends UpstreamConnector {
        private final Queue<SentMessage> outbound = new ArrayDeque<SentMessage>();

        private boolean available = true;
        private Runnable onNextSend;

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
            publishConnected();
        }

        private void disconnect() {
            synchronized (this) {
                if (!available) {
                    return;
                }
                available = false;
            }
            publishDisconnected();
        }

        private UpstreamGateway.ReceiveResult receiveTestInput(NodeId nodeId, String encodedMessage) {
            synchronized (this) {
                if (!available) {
                    throw new IllegalStateException("test connector is not connected");
                }
            }
            return receiveInput(nodeId, encodedMessage);
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
