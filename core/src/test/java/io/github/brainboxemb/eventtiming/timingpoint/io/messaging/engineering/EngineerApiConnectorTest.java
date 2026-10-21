package io.github.brainboxemb.eventtiming.timingpoint.io.messaging.engineering;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamConnector;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGateway;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamMessage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class EngineerApiConnectorTest {
    private final ScheduledThreadPoolExecutor scheduler =
            new ScheduledThreadPoolExecutor(1);

    @After
    public void closeScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    public void heartbeatCadenceExistsOnlyWhileSessionIsConnected()
            throws Exception {
        EngineerApiConnector connector =
                new EngineerApiConnector(
                        scheduler,
                        8,
                        Duration.ofMillis(20L));
        TestListener listener =
                new TestListener();
        CountDownLatch firstTicks =
                new CountDownLatch(2);
        listener.heartbeat =
                () -> {
                    listener.heartbeatCount.incrementAndGet();
                    firstTicks.countDown();
                };
        connector.bind(listener);

        EngineerApiConnector.Output firstPeer =
                message -> true;
        assertTrue(connector.connect(firstPeer));
        assertEquals(1, listener.connectedCount.get());
        assertTrue(
                firstTicks.await(
                        1L,
                        TimeUnit.SECONDS));

        connector.disconnect(firstPeer);
        assertEquals(1, listener.disconnectedCount.get());
        int afterDisconnect =
                listener.heartbeatCount.get();
        Thread.sleep(80L);
        assertEquals(
                afterDisconnect,
                listener.heartbeatCount.get());

        EngineerApiConnector.Output secondPeer =
                message -> true;
        assertTrue(connector.connect(secondPeer));
        assertEquals(2, listener.connectedCount.get());

        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(1L);
        while (listener.heartbeatCount.get() == afterDisconnect
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(
                listener.heartbeatCount.get() > afterDisconnect);

        connector.disconnect(secondPeer);
    }

    @Test
    public void receiveConvertsApiMessageToGenericRoutingEnvelope() {
        EngineerApiConnector connector =
                new EngineerApiConnector(scheduler);
        TestListener listener =
                new TestListener();
        connector.bind(listener);

        String payload =
                "{\"v\":1,\"type\":\"SYNC\",\"nodeId\":\"A\","
                        + "\"data\":{\"stream\":\"RACE_DATA\",\"seq\":0}}";
        UpstreamApiMessage input =
                UpstreamApiMessage.parse(payload);

        EngineerApiConnector.Output peer =
                message -> true;
        assertTrue(connector.connect(peer));

        listener.receiveResult =
                UpstreamGateway.ReceiveResult.ACCEPTED;
        assertSame(
                UpstreamGateway.ReceiveResult.ACCEPTED,
                connector.receive(input));
        assertEquals(
                new NodeId("A"),
                listener.received.nodeId());
        assertEquals(
                payload,
                listener.received.payload());

        listener.receiveResult =
                UpstreamGateway.ReceiveResult.UNKNOWN_NODE;
        assertSame(
                UpstreamGateway.ReceiveResult.UNKNOWN_NODE,
                connector.receive(input));

        connector.disconnect(peer);
    }

    @Test
    public void outboundMessagesKeepConnectorOrder() {
        EngineerApiConnector connector =
                new EngineerApiConnector(scheduler);
        TestListener listener =
                new TestListener();
        connector.bind(listener);

        List<UpstreamApiMessage> output =
                new ArrayList<UpstreamApiMessage>();
        EngineerApiConnector.Output peer =
                message -> output.add(message);
        assertTrue(connector.connect(peer));

        UpstreamMessage first =
                new UpstreamMessage(
                        new NodeId("A"),
                        "first");
        UpstreamMessage second =
                new UpstreamMessage(
                        new NodeId("A"),
                        "second");

        connector.send(first);
        connector.send(second);

        assertEquals(2, output.size());
        assertEquals(
                "first",
                output.get(0).payload());
        assertEquals(
                "second",
                output.get(1).payload());

        connector.disconnect(peer);
        assertFalse(connector.isAvailable());
    }

    private static final class TestListener
            implements UpstreamConnector.Listener {
        private final AtomicInteger connectedCount =
                new AtomicInteger();
        private final AtomicInteger disconnectedCount =
                new AtomicInteger();
        private final AtomicInteger heartbeatCount =
                new AtomicInteger();

        private UpstreamGateway.ReceiveResult receiveResult =
                UpstreamGateway.ReceiveResult.ACCEPTED;
        private UpstreamMessage received;
        private Runnable heartbeat =
                heartbeatCount::incrementAndGet;

        @Override
        public UpstreamGateway.ReceiveResult receive(
                UpstreamMessage message) {
            received = message;
            return receiveResult;
        }

        @Override
        public void connected() {
            connectedCount.incrementAndGet();
        }

        @Override
        public void disconnected() {
            disconnectedCount.incrementAndGet();
        }

        @Override
        public void heartbeatTick() {
            heartbeat.run();
        }
    }
}
