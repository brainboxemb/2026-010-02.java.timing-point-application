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
    public void routesInboundOnceAndThenUsesIndependentApplicationPortQueues() {
        UpstreamGateway gateway = new UpstreamGateway();
        TestConnector connector = new TestConnector();
        AtomicInteger dataA = new AtomicInteger();
        AtomicInteger dataB = new AtomicInteger();

        UpstreamStream streamA = gateway.createApplicationPort(new NodeId("A"), connector);
        UpstreamStream streamB = gateway.createApplicationPort(new NodeId("B"), connector);
        streamA.DATA_AVAILABLE.subscribe(source -> dataA.incrementAndGet());
        streamB.DATA_AVAILABLE.subscribe(source -> dataB.incrementAndGet());

        connector.connect();

        assertSame(UpstreamGateway.ReceiveResult.ACCEPTED, connector.receiveInput(new NodeId("A"), "message-a"));
        assertSame(UpstreamGateway.ReceiveResult.ACCEPTED, connector.receiveInput(new NodeId("B"), "message-b"));
        assertEquals(1, dataA.get());
        assertEquals(1, dataB.get());

        assertEquals("message-a", streamA.read());
        assertEquals("message-b", streamB.read());
        assertFalse(streamA.hasMessage());
        assertFalse(streamB.hasMessage());

        streamB.send("outbound-b");

        assertEquals(1, connector.sent.size());
        assertEquals(new NodeId("B"), connector.sent.get(0).nodeId);
        assertEquals("outbound-b", connector.sent.get(0).payload);
    }

    @Test
    public void connectorConnectionEventReachesOnlyItsBoundApplicationPorts() {
        UpstreamGateway gateway = new UpstreamGateway();
        TestConnector first = new TestConnector();
        TestConnector second = new TestConnector();
        AtomicInteger connectedA = new AtomicInteger();
        AtomicInteger connectedB = new AtomicInteger();

        UpstreamStream streamA = gateway.createApplicationPort(new NodeId("A"), first);
        UpstreamStream streamB = gateway.createApplicationPort(new NodeId("B"), second);
        streamA.CONNECTED.subscribe(source -> {
            assertSame(streamA, source);
            connectedA.incrementAndGet();
        });
        streamB.CONNECTED.subscribe(source -> {
            assertSame(streamB, source);
            connectedB.incrementAndGet();
        });

        first.connect();

        assertEquals(1, connectedA.get());
        assertEquals(0, connectedB.get());

        second.connect();

        assertEquals(1, connectedA.get());
        assertEquals(1, connectedB.get());
    }

    @Test
    public void disconnectClearsUnreadInputForThatConnectorSession() {
        UpstreamGateway gateway = new UpstreamGateway();
        TestConnector connector = new TestConnector();
        UpstreamStream stream = gateway.createApplicationPort(new NodeId("A"), connector);
        connector.connect();

        assertSame(UpstreamGateway.ReceiveResult.ACCEPTED, connector.receiveInput(new NodeId("A"), "queued"));
        assertTrue(stream.hasMessage());

        connector.disconnect();

        assertFalse(stream.hasMessage());
        assertFalse(stream.isAvailable());
    }

    @Test
    public void fullApplicationPortRejectsOnlyAdditionalInput() {
        UpstreamGateway gateway = new UpstreamGateway(1);
        TestConnector connector = new TestConnector();
        UpstreamStream stream = gateway.createApplicationPort(new NodeId("A"), connector);
        connector.connect();

        assertSame(UpstreamGateway.ReceiveResult.ACCEPTED, connector.receiveInput(new NodeId("A"), "first"));
        assertSame(UpstreamGateway.ReceiveResult.INPUT_FULL, connector.receiveInput(new NodeId("A"), "second"));

        assertEquals("first", stream.read());
        assertFalse(stream.hasMessage());
    }

    private static final class SentMessage {
        private final NodeId nodeId;
        private final String payload;

        private SentMessage(NodeId nodeId, String payload) {
            this.nodeId = nodeId;
            this.payload = payload;
        }
    }

    /** Transport double using the same shared connector base as production transports. */
    private static final class TestConnector extends UpstreamConnector {
        private final List<SentMessage> sent = new ArrayList<SentMessage>();
        private boolean available;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public void send(NodeId nodeId, String encodedMessage) {
            if (!available) {
                throw new IllegalStateException("connector is not available");
            }
            sent.add(new SentMessage(nodeId, encodedMessage));
        }

        private void connect() {
            available = true;
            publishConnected();
        }

        private void disconnect() {
            available = false;
            publishDisconnected();
        }
    }
}
