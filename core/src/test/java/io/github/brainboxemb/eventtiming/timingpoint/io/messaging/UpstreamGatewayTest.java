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

public class UpstreamGatewayTest {

    @Test
    public void routesIndependentNodeQueuesThroughOneConnector() {
        UpstreamGateway gateway =
                new UpstreamGateway();
        TestConnector connector =
                new TestConnector();
        AtomicInteger wakeA =
                new AtomicInteger();
        AtomicInteger wakeB =
                new AtomicInteger();

        gateway.bindNode(
                new NodeId("A"),
                connector,
                () -> {
                    wakeA.incrementAndGet();
                    return true;
                },
                () -> {
                },
                () -> {
                });
        gateway.bindNode(
                new NodeId("B"),
                connector,
                () -> {
                    wakeB.incrementAndGet();
                    return true;
                },
                () -> {
                },
                () -> {
                });
        connector.connect();

        UpstreamMessage inputA =
                UpstreamMessage.from(
                        DefaultUpstreamData.sync(
                                new NodeId("A"),
                                UpstreamData.Stream.RACE_DATA,
                                1L));
        UpstreamMessage inputB =
                UpstreamMessage.from(
                        DefaultUpstreamData.sync(
                                new NodeId("B"),
                                UpstreamData.Stream.RACE_DATA,
                                2L));

        assertSame(
                UpstreamGateway.ReceiveResult.ACCEPTED,
                connector.receive(inputA));
        assertSame(
                UpstreamGateway.ReceiveResult.ACCEPTED,
                connector.receive(inputB));
        assertEquals(1, wakeA.get());
        assertEquals(1, wakeB.get());

        assertSame(inputA, gateway.read(new NodeId("A")));
        assertSame(inputB, gateway.read(new NodeId("B")));
        assertFalse(gateway.hasMessage(new NodeId("A")));
        assertFalse(gateway.hasMessage(new NodeId("B")));

        UpstreamMessage outbound =
                UpstreamMessage.from(
                        DefaultUpstreamData.ping(
                                new NodeId("B"),
                                UpstreamData.PingOperation.REQ,
                                null,
                                "p-1"));
        gateway.send(outbound);

        assertEquals(1, connector.sent.size());
        assertSame(outbound, connector.sent.get(0));
    }

    @Test
    public void connectorLifecycleIsForwardedOnlyToItsBoundNodes() {
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

        gateway.bindNode(
                new NodeId("A"),
                first,
                () -> true,
                connectedA::incrementAndGet,
                heartbeatA::incrementAndGet);
        gateway.bindNode(
                new NodeId("B"),
                second,
                () -> true,
                connectedB::incrementAndGet,
                heartbeatB::incrementAndGet);

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

        gateway.bindNode(
                new NodeId("A"),
                connector,
                () -> true,
                () -> {
                },
                () -> {
                });
        connector.connect();

        UpstreamMessage message =
                UpstreamMessage.from(
                        DefaultUpstreamData.sync(
                                new NodeId("A"),
                                UpstreamData.Stream.RACE_DATA,
                                1L));
        assertSame(
                UpstreamGateway.ReceiveResult.ACCEPTED,
                connector.receive(message));
        assertTrue(gateway.hasMessage(new NodeId("A")));

        connector.disconnect();

        assertFalse(gateway.hasMessage(new NodeId("A")));
        assertFalse(gateway.isAvailable(new NodeId("A")));
    }

    @Test
    public void rejectedWakeDoesNotLeaveMessageQueued() {
        UpstreamGateway gateway =
                new UpstreamGateway();
        TestConnector connector =
                new TestConnector();

        gateway.bindNode(
                new NodeId("A"),
                connector,
                () -> false,
                () -> {
                },
                () -> {
                });
        connector.connect();

        assertSame(
                UpstreamGateway.ReceiveResult.INPUT_REJECTED,
                connector.receive(
                        UpstreamMessage.from(
                                DefaultUpstreamData.sync(
                                        new NodeId("A"),
                                        UpstreamData.Stream.RACE_DATA,
                                        0L))));
        assertFalse(gateway.hasMessage(new NodeId("A")));
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
