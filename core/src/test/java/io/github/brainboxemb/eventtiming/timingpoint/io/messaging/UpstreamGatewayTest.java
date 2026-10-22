package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class UpstreamGatewayTest {

    @Test
    public void routesInboundThroughFixedApplicationPorts() {
        TestConnector connector = new TestConnector();
        NodeId nodeA = new NodeId("A");
        NodeId nodeB = new NodeId("B");
        UpstreamStream streamA = new UpstreamStream(nodeA, connector);
        UpstreamStream streamB = new UpstreamStream(nodeB, connector);
        AtomicInteger dataA = new AtomicInteger();
        AtomicInteger dataB = new AtomicInteger();

        Map<NodeId, UpstreamStream> streams = new LinkedHashMap<NodeId, UpstreamStream>();
        streams.put(nodeA, streamA);
        streams.put(nodeB, streamB);
        new UpstreamGateway(connector, streams);

        streamA.DATA_AVAILABLE.subscribe(source -> dataA.incrementAndGet());
        streamB.DATA_AVAILABLE.subscribe(source -> dataB.incrementAndGet());
        connector.connect();

        assertSame(UpstreamGateway.ReceiveResult.ACCEPTED, connector.receiveInput(nodeA, "message-a"));
        assertSame(UpstreamGateway.ReceiveResult.ACCEPTED, connector.receiveInput(nodeB, "message-b"));
        assertEquals(1, dataA.get());
        assertEquals(1, dataB.get());

        assertEquals("message-a", streamA.read());
        assertEquals("message-b", streamB.read());
        assertFalse(streamA.hasMessage());
        assertFalse(streamB.hasMessage());

        streamB.send("outbound-b");

        assertEquals(1, connector.sent.size());
        assertEquals(nodeB, connector.sent.get(0).nodeId);
        assertEquals("outbound-b", connector.sent.get(0).payload);
    }

    @Test
    public void connectorConnectionEventReachesOnlyItsFixedStreams() {
        TestConnector first = new TestConnector();
        TestConnector second = new TestConnector();
        UpstreamStream streamA = new UpstreamStream(new NodeId("A"), first);
        UpstreamStream streamB = new UpstreamStream(new NodeId("B"), second);
        AtomicInteger connectedA = new AtomicInteger();
        AtomicInteger connectedB = new AtomicInteger();

        new UpstreamGateway(first, Collections.singletonMap(new NodeId("A"), streamA));
        new UpstreamGateway(second, Collections.singletonMap(new NodeId("B"), streamB));

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
    public void disconnectClearsUnreadSessionInput() {
        TestConnector connector = new TestConnector();
        NodeId nodeId = new NodeId("A");
        UpstreamStream stream = new UpstreamStream(nodeId, connector);
        new UpstreamGateway(connector, Collections.singletonMap(nodeId, stream));
        connector.connect();

        assertSame(UpstreamGateway.ReceiveResult.ACCEPTED, connector.receiveInput(nodeId, "queued"));
        assertTrue(stream.hasMessage());

        connector.disconnect();

        assertFalse(stream.hasMessage());
        assertFalse(stream.isAvailable());
    }

    @Test
    public void fullStreamRejectsOnlyAdditionalInput() {
        TestConnector connector = new TestConnector();
        NodeId nodeId = new NodeId("A");
        UpstreamStream stream = new UpstreamStream(nodeId, connector, 1);
        new UpstreamGateway(connector, Collections.singletonMap(nodeId, stream));
        connector.connect();

        assertSame(UpstreamGateway.ReceiveResult.ACCEPTED, connector.receiveInput(nodeId, "first"));
        assertSame(UpstreamGateway.ReceiveResult.INPUT_FULL, connector.receiveInput(nodeId, "second"));

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

    /** The transport double inherits the same fixed gateway-input contract as production connectors. */
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
