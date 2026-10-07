package io.github.brainboxemb.eventtiming.systemtest.framework;

import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

/** Queue-backed WebSocket event client for black-box verification. */
public final class EventStream extends WebSocketClient {
    private static final String LOOPBACK = "127.0.0.1";

    private final LinkedBlockingQueue<String> messages =
            new LinkedBlockingQueue<String>();
    private final AtomicReference<Exception> failure =
            new AtomicReference<Exception>();

    private EventStream(URI uri) {
        super(uri);
    }

    public static EventStream connect(int port) throws Exception {
        URI uri = URI.create(
                "ws://" + LOOPBACK + ":" + port + "/api/v1/events");
        long deadline = System.currentTimeMillis() + 5000L;
        Exception lastFailure = null;

        /*
         * HTTP and WebSocket listeners are started independently. A black-box
         * test may observe HTTP readiness a few milliseconds before the
         * WebSocket listener is accepting connections, especially after a
         * restart. Retry fresh clients within one bounded deadline rather than
         * adding timing sleeps to individual verification cases.
         */
        while (System.currentTimeMillis() < deadline) {
            EventStream stream = new EventStream(uri);
            long remaining =
                    Math.max(
                            1L,
                            deadline - System.currentTimeMillis());
            try {
                if (stream.connectBlocking(
                        Math.min(500L, remaining),
                        TimeUnit.MILLISECONDS)) {
                    stream.throwFailure();
                    return stream;
                }
                if (stream.failure.get() != null) {
                    lastFailure = stream.failure.get();
                }
            } catch (Exception ex) {
                lastFailure = ex;
            }

            stream.close();
            if (System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
        }

        AssertionError timeout =
                new AssertionError("Timed out opening IF-03 WebSocket");
        if (lastFailure != null) {
            timeout.initCause(lastFailure);
        }
        throw timeout;
    }

    public String awaitEvent(String eventType) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            throwFailure();
            long remaining = deadline - System.currentTimeMillis();
            String message = messages.poll(
                    Math.max(1L, remaining),
                    TimeUnit.MILLISECONDS);
            if (message == null) {
                break;
            }
            if (message.contains("\"eventType\":\"" + eventType + "\"")) {
                return message;
            }
        }
        throw new AssertionError(
                "Timed out waiting for IF-03 event " + eventType);
    }

    public void assertNoEvent(String eventType, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            throwFailure();
            long remaining = deadline - System.currentTimeMillis();
            String message = messages.poll(
                    Math.max(1L, remaining),
                    TimeUnit.MILLISECONDS);
            if (message == null) {
                return;
            }
            if (message.contains("\"eventType\":\"" + eventType + "\"")) {
                throw new AssertionError(
                        "Unexpected IF-03 event "
                                + eventType
                                + ": "
                                + message);
            }
        }
    }

    public void closeQuietly() {
        try {
            closeBlocking();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void throwFailure() throws Exception {
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
    }

    @Override
    public void onMessage(String message) {
        messages.offer(message);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
    }

    @Override
    public void onError(Exception ex) {
        failure.compareAndSet(null, ex);
    }
}
