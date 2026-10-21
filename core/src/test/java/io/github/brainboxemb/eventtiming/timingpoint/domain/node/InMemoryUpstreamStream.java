package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamConnector;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGateway;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamMessage;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/** Test fixture around the real gateway-backed UpstreamStream. */
final class InMemoryUpstreamStream {
    private static final long SEND_WAIT_MILLIS = 1000L;

    private NodeId nodeId;
    private final UpstreamDataCodec codec =
            new DefaultUpstreamDataCodec(
                    new DefaultTimingDataCodec());
    private final TestConnector connector =
            new TestConnector();
    private UpstreamStream stream;

    InMemoryUpstreamStream() {
    }

    UpstreamStream bind(
            NodeId requestedNodeId) {
        if (stream == null) {
            nodeId = requestedNodeId;
            UpstreamGateway gateway =
                    new UpstreamGateway();
            stream =
                    gateway.createStream(
                            nodeId,
                            connector);
        } else if (!nodeId.equals(requestedNodeId)) {
            throw new IllegalStateException(
                    "test stream is already bound to another node");
        }
        return stream;
    }

    UpstreamStream stream() {
        if (stream == null) {
            throw new IllegalStateException(
                    "test stream is not bound");
        }
        return stream;
    }

    void connect() {
        connector.connect();
    }

    void disconnect() {
        connector.disconnect();
    }

    void setAvailable(
            boolean available) {
        if (available) {
            connector.connect();
        } else {
            connector.disconnect();
        }
    }

    void heartbeat() {
        connector.heartbeat();
    }

    void receive(
            UpstreamData data) {
        if (!(data instanceof UpstreamData.NodeScoped)) {
            throw new IllegalArgumentException(
                    "test UpstreamData must be node-scoped");
        }
        UpstreamData.NodeScoped nodeScoped =
                (UpstreamData.NodeScoped) data;
        if (!nodeId.equals(nodeScoped.timingNodeId())) {
            throw new IllegalArgumentException(
                    "test UpstreamData belongs to another node");
        }

        try {
            String encoded =
                    new String(
                            codec.encode(data),
                            StandardCharsets.UTF_8);
            UpstreamGateway.ReceiveResult result =
                    connector.receive(
                            new UpstreamMessage(
                                    nodeId,
                                    encoded));
            if (result != UpstreamGateway.ReceiveResult.ACCEPTED) {
                throw new IllegalStateException(
                        "test upstream input was not accepted: "
                                + result);
            }
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalStateException(
                    "could not encode test UpstreamData",
                    ex);
        }
    }

    UpstreamData takeSent() {
        UpstreamMessage message =
                connector.takeSent();
        try {
            return codec.decode(
                    message.payload()
                            .getBytes(
                                    StandardCharsets.UTF_8));
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalStateException(
                    "could not decode test UpstreamData",
                    ex);
        }
    }

    int sentCount() {
        return connector.sentCount();
    }

    void onNextSend(
            Runnable action) {
        connector.onNextSend(action);
    }

    private static final class TestConnector
            implements UpstreamConnector {
        private final Queue<UpstreamMessage> outbound =
                new ArrayDeque<UpstreamMessage>();
        private Listener listener;
        private boolean available = true;
        private Runnable onNextSend;

        @Override
        public synchronized void bind(
                Listener newListener) {
            if (listener != null) {
                throw new IllegalStateException(
                        "test connector already bound");
            }
            listener = newListener;
        }

        @Override
        public synchronized boolean isAvailable() {
            return available;
        }

        @Override
        public void send(
                UpstreamMessage message) {
            Runnable action;
            synchronized (this) {
                if (!available) {
                    throw new IllegalStateException(
                            "test connector is not connected");
                }
                outbound.add(message);
                action = onNextSend;
                onNextSend = null;
                notifyAll();
            }
            if (action != null) {
                action.run();
            }
        }

        private void connect() {
            Listener current;
            synchronized (this) {
                if (available) {
                    return;
                }
                available = true;
                current = listener;
            }
            if (current != null) {
                current.connected();
            }
        }

        private void disconnect() {
            Listener current;
            synchronized (this) {
                if (!available) {
                    return;
                }
                available = false;
                current = listener;
            }
            if (current != null) {
                current.disconnected();
            }
        }

        private void heartbeat() {
            listener.heartbeatTick();
        }

        private UpstreamGateway.ReceiveResult receive(
                UpstreamMessage message) {
            synchronized (this) {
                if (!available) {
                    throw new IllegalStateException(
                            "test connector is not connected");
                }
            }
            return listener.receive(message);
        }

        private UpstreamMessage takeSent() {
            long deadline =
                    System.nanoTime()
                            + TimeUnit.MILLISECONDS.toNanos(
                                    SEND_WAIT_MILLIS);

            synchronized (this) {
                while (outbound.isEmpty()) {
                    long remainingNanos =
                            deadline - System.nanoTime();
                    if (remainingNanos <= 0L) {
                        throw new IllegalStateException(
                                "no outbound UpstreamMessage available");
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(
                                this,
                                remainingNanos);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "interrupted while waiting for outbound UpstreamMessage",
                                ex);
                    }
                }
                return outbound.remove();
            }
        }

        private synchronized int sentCount() {
            return outbound.size();
        }

        private synchronized void onNextSend(
                Runnable action) {
            if (action == null) {
                throw new IllegalArgumentException(
                        "action must not be null");
            }
            onNextSend = action;
        }
    }
}
