package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

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
