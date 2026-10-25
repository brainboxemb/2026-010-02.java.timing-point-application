package oss.relay.eventtiming.timingpoint.io.messaging.engineering;

import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingpoint.io.messaging.UpstreamGateway;
import oss.relay.eventtiming.timingpoint.io.messaging.UpstreamStream;

import java.util.ArrayList;
import java.util.Collections;
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
        NodeId nodeId = new NodeId("A");
        UpstreamStream stream = new UpstreamStream(nodeId, connector);
        new UpstreamGateway(connector, Collections.singletonMap(nodeId, stream));

        AtomicInteger connected = new AtomicInteger();
        AtomicInteger disconnected = new AtomicInteger();

        connector.CONNECTED.subscribe(source -> {
            assertSame(connector, source);
            connected.incrementAndGet();
        });
        connector.DISCONNECTED.subscribe(source -> {
            assertSame(connector, source);
            disconnected.incrementAndGet();
        });

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
    public void receiveRoutesEncodedPayloadThroughFixedGateway() {
        EngineerApiConnector connector = new EngineerApiConnector();
        NodeId nodeId = new NodeId("A");
        UpstreamStream stream = new UpstreamStream(nodeId, connector);
        new UpstreamGateway(connector, Collections.singletonMap(nodeId, stream));

        EngineerApiConnector.Output peer = output -> true;
        assertTrue(connector.connect(peer));

        String payload =
                "{\"v\":1,\"type\":\"SYNC\",\"nodeId\":\"A\","
                        + "\"data\":{\"stream\":\"RACE_DATA\",\"seq\":0}}";
        UpstreamApiMessage message = UpstreamApiMessage.parse(payload);

        assertSame(UpstreamGateway.ReceiveResult.ACCEPTED, connector.receive(message));
        assertEquals(payload, stream.read());

        String unknownPayload =
                "{\"v\":1,\"type\":\"SYNC\",\"nodeId\":\"B\","
                        + "\"data\":{\"stream\":\"RACE_DATA\",\"seq\":0}}";
        assertSame(
                UpstreamGateway.ReceiveResult.UNKNOWN_NODE,
                connector.receive(UpstreamApiMessage.parse(unknownPayload)));

        connector.disconnect(peer);
    }

    @Test
    public void outboundMessagesKeepConnectorOrderWithoutGenericEnvelope() {
        EngineerApiConnector connector = new EngineerApiConnector();
        NodeId nodeId = new NodeId("A");
        UpstreamStream stream = new UpstreamStream(nodeId, connector);
        new UpstreamGateway(connector, Collections.singletonMap(nodeId, stream));

        List<UpstreamApiMessage> output = new ArrayList<UpstreamApiMessage>();
        EngineerApiConnector.Output peer = output::add;
        assertTrue(connector.connect(peer));

        stream.send("first");
        stream.send("second");

        assertEquals(2, output.size());
        assertEquals(nodeId, output.get(0).nodeId());
        assertEquals("first", output.get(0).payload());
        assertEquals(nodeId, output.get(1).nodeId());
        assertEquals("second", output.get(1).payload());

        connector.disconnect(peer);
        assertFalse(connector.isAvailable());
    }
}
