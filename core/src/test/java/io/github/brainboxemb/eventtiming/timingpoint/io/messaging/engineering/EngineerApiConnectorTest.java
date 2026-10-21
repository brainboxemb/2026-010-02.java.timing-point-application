package io.github.brainboxemb.eventtiming.timingpoint.io.messaging.engineering;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamConnector;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGateway;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class EngineerApiConnectorTest {

    @Test
    public void connectionLifecycleUsesTypedConnectorEvents() {
        EngineerApiConnector connector = new EngineerApiConnector();
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger disconnected = new AtomicInteger();

        connector.connectedEvent().subscribe(source -> {
            assertSame(connector, source);
            connected.incrementAndGet();
        });
        connector.disconnectedEvent().subscribe(source -> {
            assertSame(connector, source);
            disconnected.incrementAndGet();
        });
        connector.bindInput((nodeId, payload) -> UpstreamGateway.ReceiveResult.ACCEPTED);

        EngineerApiConnector.Output firstPeer = message -> true;
        assertTrue(connector.connect(firstPeer));
        assertEquals(1, connected.get());
        assertTrue(connector.isAvailable());

        connector.disconnect(firstPeer);
        assertEquals(1, disconnected.get());
        assertFalse(connector.isAvailable());

        EngineerApiConnector.Output secondPeer = message -> true;
        assertTrue(connector.connect(secondPeer));
        assertEquals(2, connected.get());

        connector.disconnect(secondPeer);
        assertEquals(2, disconnected.get());
    }

    @Test
    public void receivePassesRoutingIdentitySeparatelyFromEncodedPayload() {
        EngineerApiConnector connector = new EngineerApiConnector();
        RecordingInput input = new RecordingInput();
        connector.bindInput(input);

        String payload =
                "{\"v\":1,\"type\":\"SYNC\",\"nodeId\":\"A\","
                        + "\"data\":{\"stream\":\"RACE_DATA\",\"seq\":0}}";
        UpstreamApiMessage message = UpstreamApiMessage.parse(payload);

        EngineerApiConnector.Output peer = output -> true;
        assertTrue(connector.connect(peer));

        input.result = UpstreamGateway.ReceiveResult.ACCEPTED;
        assertSame(UpstreamGateway.ReceiveResult.ACCEPTED, connector.receive(message));
        assertEquals(new NodeId("A"), input.nodeId);
        assertEquals(payload, input.payload);

        input.result = UpstreamGateway.ReceiveResult.UNKNOWN_NODE;
        assertSame(UpstreamGateway.ReceiveResult.UNKNOWN_NODE, connector.receive(message));

        connector.disconnect(peer);
    }

    @Test
    public void outboundMessagesKeepConnectorOrderWithoutGenericEnvelope() {
        EngineerApiConnector connector = new EngineerApiConnector();
        connector.bindInput((nodeId, payload) -> UpstreamGateway.ReceiveResult.ACCEPTED);

        List<UpstreamApiMessage> output = new ArrayList<UpstreamApiMessage>();
        EngineerApiConnector.Output peer = message -> output.add(message);
        assertTrue(connector.connect(peer));

        connector.send(new NodeId("A"), "first");
        connector.send(new NodeId("A"), "second");

        assertEquals(2, output.size());
        assertEquals(new NodeId("A"), output.get(0).nodeId());
        assertEquals("first", output.get(0).payload());
        assertEquals(new NodeId("A"), output.get(1).nodeId());
        assertEquals("second", output.get(1).payload());

        connector.disconnect(peer);
        assertFalse(connector.isAvailable());
    }

    private static final class RecordingInput implements UpstreamGateway.ConnectorPort {
        private UpstreamGateway.ReceiveResult result = UpstreamGateway.ReceiveResult.ACCEPTED;
        private NodeId nodeId;
        private String payload;

        @Override
        public UpstreamGateway.ReceiveResult receive(NodeId newNodeId, String encodedMessage) {
            nodeId = newNodeId;
            payload = encodedMessage;
            return result;
        }
    }
}
