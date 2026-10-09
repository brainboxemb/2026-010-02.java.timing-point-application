package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;

import org.java_websocket.WebSocket;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class WebSocketOutboundDeliveryTest {

    @Test
    public void disconnectsBeforeSendingBeyondBufferedLimit() {
        TestConnection testConnection =
                new TestConnection();
        WebSocket connection =
                testConnection.proxy();
        WebSocketOutboundDelivery delivery =
                new WebSocketOutboundDelivery(
                        3);
        delivery.connected(
                connection);

        assertEquals(
                WebSocketOutboundDelivery.SendResult.SENT,
                delivery.send(
                        connection,
                        "one"));
        assertEquals(
                WebSocketOutboundDelivery.SendResult.SENT,
                delivery.send(
                        connection,
                        "two"));
        assertEquals(
                WebSocketOutboundDelivery.SendResult.SENT,
                delivery.send(
                        connection,
                        "three"));

        assertEquals(
                WebSocketOutboundDelivery.SendResult.OVERLOADED,
                delivery.send(
                        connection,
                        "four"));

        assertEquals(
                3,
                testConnection.sendCount);
        assertEquals(
                1,
                testConnection.closeCount);
        assertEquals(
                WebSocketOutboundDelivery.TRY_AGAIN_LATER_CLOSE_CODE,
                testConnection.closeCode);

        assertEquals(
                WebSocketOutboundDelivery.SendResult.NOT_CONNECTED,
                delivery.send(
                        connection,
                        "five"));
        assertEquals(
                3,
                testConnection.sendCount);
    }

    @Test
    public void observedFullDrainResetsBufferedSendBudget() {
        TestConnection testConnection =
                new TestConnection();
        WebSocket connection =
                testConnection.proxy();
        WebSocketOutboundDelivery delivery =
                new WebSocketOutboundDelivery(
                        2);
        delivery.connected(
                connection);

        delivery.send(
                connection,
                "one");
        delivery.send(
                connection,
                "two");

        testConnection.buffered = false;

        assertEquals(
                WebSocketOutboundDelivery.SendResult.SENT,
                delivery.send(
                        connection,
                        "after-drain"));
        assertEquals(
                3,
                testConnection.sendCount);
        assertEquals(
                0,
                testConnection.closeCount);
    }

    @Test
    public void overloadedClientDoesNotStopTimingNodeCommitProgress() {
        TestConnection testConnection =
                new TestConnection();
        WebSocket connection =
                testConnection.proxy();
        WebSocketOutboundDelivery delivery =
                new WebSocketOutboundDelivery(
                        3);
        delivery.connected(
                connection);

        TimingNode node =
                TimingNodeFixture.create(
                        new NodeId("A"),
                        new MemoryStore(),
                        () -> TimingTimestamp
                                .parse("2026-10-07T10:00:00Z")
                                .instant());
        node.activate();
        try {
            node.timingDataCommittedEvent()
                    .subscribe(
                            data -> {
                                delivery.send(
                                        connection,
                                        "seq-" + data.sequenceNumber());
                            });

            node.invoke(
                    TimingNodeCommands.open(
                            new LocationId(24)));

            for (int index = 1; index <= 10; index++) {
                node.invoke(
                        TimingNodeCommands.addAutomaticRegistration(
                                new RegistrationId(
                                        String.format(
                                                "N%04d",
                                                index)),
                                TimingTimestamp.parse(
                                        String.format(
                                                "2026-10-07T10:00:%02dZ",
                                                index))));
            }

            assertEquals(
                    "all TimingNode commits must complete even after the slow client is disconnected",
                    11,
                    node.query(
                            TimingNodeQueries.timingDataCount())
                            .intValue());
            assertEquals(
                    "the transport must not send beyond its bounded backlog budget",
                    3,
                    testConnection.sendCount);
            assertEquals(
                    "the stalled client must be disconnected exactly once",
                    1,
                    testConnection.closeCount);
            assertEquals(
                    WebSocketOutboundDelivery.TRY_AGAIN_LATER_CLOSE_CODE,
                    testConnection.closeCode);
        } finally {
            node.deactivate();
        }
    }

    private static final class MemoryStore
            implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(
                TimingData data) {
        }
    }

    private static final class TestConnection
            implements InvocationHandler {
        private boolean open = true;
        private boolean buffered;
        private int sendCount;
        private int closeCount;
        private int closeCode;

        private WebSocket proxy() {
            return (WebSocket) Proxy.newProxyInstance(
                    WebSocket.class.getClassLoader(),
                    new Class<?>[] {
                        WebSocket.class
                    },
                    this);
        }

        @Override
        public Object invoke(
                Object proxy,
                Method method,
                Object[] arguments) {
            String name =
                    method.getName();

            if ("hashCode".equals(name)) {
                return Integer.valueOf(
                        System.identityHashCode(
                                proxy));
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(
                        proxy == arguments[0]);
            }
            if ("isOpen".equals(name)) {
                return Boolean.valueOf(
                        open);
            }
            if ("hasBufferedData".equals(name)) {
                return Boolean.valueOf(
                        buffered);
            }
            if ("send".equals(name)) {
                sendCount++;
                buffered = true;
                return null;
            }
            if ("close".equals(name)) {
                closeCount++;
                closeCode =
                        ((Integer) arguments[0]).intValue();
                open = false;
                return null;
            }
            if ("toString".equals(name)) {
                return "TestWebSocket";
            }

            Class<?> returnType =
                    method.getReturnType();
            if (returnType == Boolean.TYPE) {
                return Boolean.FALSE;
            }
            if (returnType == Integer.TYPE) {
                return Integer.valueOf(0);
            }
            if (returnType == Long.TYPE) {
                return Long.valueOf(0L);
            }

            return null;
        }
    }
}
