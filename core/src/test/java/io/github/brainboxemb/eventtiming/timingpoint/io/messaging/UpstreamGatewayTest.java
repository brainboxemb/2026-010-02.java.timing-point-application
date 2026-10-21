package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class UpstreamGatewayTest {

    @Test
    public void routesIndependentNodeQueuesThroughOneConnector() {
        UpstreamGateway gateway =
                new UpstreamGateway();
        TestConnector connector =
                new TestConnector();
        AtomicInteger dataA =
                new AtomicInteger();
        AtomicInteger dataB =
                new AtomicInteger();

        UpstreamStream streamA =
                gateway.createStream(
                        new NodeId("A"),
                        connector);
        UpstreamStream streamB =
                gateway.createStream(
                        new NodeId("B"),
                        connector);
        streamA.DATA_AVAILABLE.subscribe(
                ignored -> dataA.incrementAndGet());
        streamB.DATA_AVAILABLE.subscribe(
                ignored -> dataB.incrementAndGet());

        connector.connect();

        UpstreamMessage inputA =
                new UpstreamMessage(
                        new NodeId("A"),
                        "message-a");
        UpstreamMessage inputB =
                new UpstreamMessage(
                        new NodeId("B"),
                        "message-b");

        assertSame(
                UpstreamGateway.ReceiveResult.ACCEPTED,
                connector.receive(inputA));
        assertSame(
                UpstreamGateway.ReceiveResult.ACCEPTED,
                connector.receive(inputB));
        assertEquals(1, dataA.get());
        assertEquals(1, dataB.get());

        assertEquals(
                "message-a",
                streamA.read());
        assertEquals(
                "message-b",
                streamB.read());
        assertFalse(streamA.hasMessage());
        assertFalse(streamB.hasMessage());

        streamB.send("outbound-b");

        assertEquals(1, connector.sent.size());
        assertEquals(
                new NodeId("B"),
                connector.sent.get(0).nodeId());
        assertEquals(
                "outbound-b",
                connector.sent.get(0).payload());
    }

    @Test
    public void connectorLifecycleIsForwardedOnlyToItsBoundStreams() {
        UpstreamGateway gateway =
                new UpstreamGateway();
        TestConnector first =
                new TestConnector();
        TestConnector second =
                new TestConnector();
        AtomicInteger connectedA =
                new AtomicInteger();
        AtomicInteger connectedB =
                new AtomicInteger();
        AtomicInteger heartbeatA =
                new AtomicInteger();
        AtomicInteger heartbeatB =
                new AtomicInteger();

        UpstreamStream streamA =
                gateway.createStream(
                        new NodeId("A"),
                        first);
        UpstreamStream streamB =
                gateway.createStream(
                        new NodeId("B"),
                        second);
        streamA.CONNECTED.subscribe(
                ignored -> connectedA.incrementAndGet());
        streamB.CONNECTED.subscribe(
                ignored -> connectedB.incrementAndGet());
        streamA.HEARTBEAT_TICK.subscribe(
                ignored -> heartbeatA.incrementAndGet());
        streamB.HEARTBEAT_TICK.subscribe(
                ignored -> heartbeatB.incrementAndGet());

        first.connect();
        first.heartbeat();

        assertEquals(1, connectedA.get());
        assertEquals(0, connectedB.get());
        assertEquals(1, heartbeatA.get());
        assertEquals(0, heartbeatB.get());

        second.connect();
        second.heartbeat();

        assertEquals(1, connectedB.get());
        assertEquals(1, heartbeatB.get());
    }

    @Test
    public void disconnectClearsUnreadInputForThatConnectorSession() {
        UpstreamGateway gateway =
                new UpstreamGateway();
        TestConnector connector =
                new TestConnector();
        UpstreamStream stream =
                gateway.createStream(
                        new NodeId("A"),
                        connector);
        connector.connect();

        assertSame(
                UpstreamGateway.ReceiveResult.ACCEPTED,
                connector.receive(
                        new UpstreamMessage(
                                new NodeId("A"),
                                "queued")));
        assertTrue(stream.hasMessage());

        connector.disconnect();

        assertFalse(stream.hasMessage());
        assertFalse(stream.isAvailable());
    }

    @Test
    public void fullNodeQueueRejectsOnlyAdditionalInput() {
        UpstreamGateway gateway =
                new UpstreamGateway(1);
        TestConnector connector =
                new TestConnector();
        UpstreamStream stream =
                gateway.createStream(
                        new NodeId("A"),
                        connector);
        connector.connect();

        assertSame(
                UpstreamGateway.ReceiveResult.ACCEPTED,
                connector.receive(
                        new UpstreamMessage(
                                new NodeId("A"),
                                "first")));
        assertSame(
                UpstreamGateway.ReceiveResult.INPUT_FULL,
                connector.receive(
                        new UpstreamMessage(
                                new NodeId("A"),
                                "second")));

        assertEquals(
                "first",
                stream.read());
        assertFalse(stream.hasMessage());
    }

    private static final class TestConnector
            implements UpstreamConnector {
        private final List<UpstreamMessage> sent =
                new ArrayList<UpstreamMessage>();

        private Listener listener;
        private boolean available;

        @Override
        public void bind(
                Listener newListener) {
            if (listener != null) {
                throw new IllegalStateException(
                        "connector already bound");
            }
            listener = newListener;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public void send(
                UpstreamMessage message) {
            if (!available) {
                throw new IllegalStateException(
                        "connector is not available");
            }
            sent.add(message);
        }

        private void connect() {
            available = true;
            listener.connected();
        }

        private void disconnect() {
            available = false;
            listener.disconnected();
        }

        private void heartbeat() {
            listener.heartbeatTick();
        }

        private UpstreamGateway.ReceiveResult receive(
                UpstreamMessage message) {
            return listener.receive(message);
        }
    }
}
