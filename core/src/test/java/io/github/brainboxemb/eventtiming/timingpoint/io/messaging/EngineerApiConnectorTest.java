package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

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
    public void keepsIndependentNodeInputsAndOneSharedOutputOrder() {
        EngineerApiConnector connector =
                new EngineerApiConnector();
        AtomicInteger wakeA = new AtomicInteger();
        AtomicInteger wakeB = new AtomicInteger();
        AtomicInteger connected = new AtomicInteger();

        connector.bindNode(
                new NodeId("A"),
                () -> {
                    wakeA.incrementAndGet();
                    return true;
                },
                connected::incrementAndGet);
        connector.bindNode(
                new NodeId("B"),
                () -> {
                    wakeB.incrementAndGet();
                    return true;
                },
                connected::incrementAndGet);

        List<UpstreamData> output =
                new ArrayList<UpstreamData>();
        EngineerApiConnector.Output peer =
                message -> output.add(message);
        assertTrue(connector.connect(peer));
        assertEquals(2, connected.get());

        UpstreamData.Sync inputA =
                DefaultUpstreamData.sync(
                        new NodeId("A"),
                        UpstreamData.Stream.RACE_DATA,
                        1L);
        UpstreamData.Sync inputB =
                DefaultUpstreamData.sync(
                        new NodeId("B"),
                        UpstreamData.Stream.RACE_DATA,
                        2L);

        assertSame(
                EngineerApiConnector.ReceiveResult.ACCEPTED,
                connector.receive(inputA));
        assertSame(
                EngineerApiConnector.ReceiveResult.ACCEPTED,
                connector.receive(inputB));
        assertEquals(1, wakeA.get());
        assertEquals(1, wakeB.get());

        assertTrue(connector.hasMessage(new NodeId("A")));
        assertTrue(connector.hasMessage(new NodeId("B")));
        assertSame(inputA, connector.read(new NodeId("A")));
        assertSame(inputB, connector.read(new NodeId("B")));
        assertFalse(connector.hasMessage(new NodeId("A")));
        assertFalse(connector.hasMessage(new NodeId("B")));

        UpstreamData.Ping first =
                DefaultUpstreamData.ping(
                        new NodeId("B"),
                        UpstreamData.PingOperation.REQ,
                        null,
                        "one");
        UpstreamData.Ping second =
                DefaultUpstreamData.ping(
                        new NodeId("A"),
                        UpstreamData.PingOperation.REQ,
                        null,
                        "two");
        connector.send(first);
        connector.send(second);

        assertEquals(2, output.size());
        assertSame(first, output.get(0));
        assertSame(second, output.get(1));
    }

    @Test
    public void requiresOneConnectedPeerForInboundAndOutbound() {
        EngineerApiConnector connector =
                new EngineerApiConnector();
        connector.bindNode(
                new NodeId("A"),
                () -> true,
                () -> {
                });

        UpstreamData.Sync input =
                DefaultUpstreamData.sync(
                        new NodeId("A"),
                        UpstreamData.Stream.RACE_DATA,
                        0L);

        assertSame(
                EngineerApiConnector.ReceiveResult.NOT_CONNECTED,
                connector.receive(input));

        EngineerApiConnector.Output first =
                message -> true;
        EngineerApiConnector.Output second =
                message -> true;

        assertTrue(connector.connect(first));
        assertFalse(connector.connect(second));
        connector.disconnect(first);
        assertFalse(connector.isAvailable());
    }

    @Test
    public void removesMessageWhenNodeCannotRetainWakeRequest() {
        EngineerApiConnector connector =
                new EngineerApiConnector();
        connector.bindNode(
                new NodeId("A"),
                () -> false,
                () -> {
                });
        EngineerApiConnector.Output peer =
                message -> true;
        assertTrue(connector.connect(peer));

        assertSame(
                EngineerApiConnector.ReceiveResult.INPUT_REJECTED,
                connector.receive(
                        DefaultUpstreamData.sync(
                                new NodeId("A"),
                                UpstreamData.Stream.RACE_DATA,
                                0L)));
        assertFalse(connector.hasMessage(new NodeId("A")));
    }
}
