package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.java_websocket.WebSocket;

/**
 * Bounded per-client outbound policy for the IF-03 WebSocket stream.
 *
 * <p>This helper deliberately stores no event payloads and owns no delivery
 * queue. It only counts application sends while Java-WebSocket still reports
 * buffered outbound data. A fully drained connection resets the counter.</p>
 *
 * <p>When the bound is reached, the next event is not sent. The connection is
 * closed with WebSocket code 1013 (Try Again Later), after which normal IF-03
 * reconnect/status/LogBook recovery is authoritative.</p>
 */
final class WebSocketOutboundDelivery {
    static final int DEFAULT_MAX_BUFFERED_SENDS = 32;
    static final int TRY_AGAIN_LATER_CLOSE_CODE = 1013;
    static final String BACKLOG_CLOSE_REASON =
            "IF-03 outbound backlog limit reached";

    enum SendResult {
        SENT,
        NOT_CONNECTED,
        OVERLOADED
    }

    private static final class ClientState {
        private int bufferedSendCount;
        private boolean overloaded;
    }

    private final int maxBufferedSends;
    private final ConcurrentMap<WebSocket, ClientState> clients =
            new ConcurrentHashMap<WebSocket, ClientState>();

    WebSocketOutboundDelivery() {
        this(DEFAULT_MAX_BUFFERED_SENDS);
    }

    WebSocketOutboundDelivery(
            int maxBufferedSends) {
        if (maxBufferedSends <= 0) {
            throw new IllegalArgumentException(
                    "maxBufferedSends must be positive");
        }
        this.maxBufferedSends = maxBufferedSends;
    }

    /**
     * Starts transport-local delivery tracking for one accepted connection.
     */
    void connected(
            WebSocket connection) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "connection must not be null");
        }
        clients.put(
                connection,
                new ClientState());
    }

    /**
     * Drops all transport-local state for a closed connection.
     */
    void disconnected(
            WebSocket connection) {
        if (connection != null) {
            clients.remove(
                    connection);
        }
    }

    /**
     * Offers one already encoded event to one connection without waiting.
     *
     * <p>No retry occurs here. The method either performs one non-blocking
     * WebSocket send, ignores an already-untracked/closed connection, or
     * disconnects a client whose backlog guard has been exhausted.</p>
     */
    SendResult send(
            WebSocket connection,
            String message) {
        if (connection == null) {
            throw new IllegalArgumentException(
                    "connection must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null");
        }

        ClientState state =
                clients.get(
                        connection);
        if (state == null) {
            return SendResult.NOT_CONNECTED;
        }

        synchronized (state) {
            if (state.overloaded
                    || !connection.isOpen()) {
                return SendResult.NOT_CONNECTED;
            }

            /*
             * Java-WebSocket exposes whether outbound data remains buffered but
             * not an application-owned bounded queue. Observe a full drain as
             * the only safe point to reset our conservative send budget.
             */
            if (!connection.hasBufferedData()) {
                state.bufferedSendCount = 0;
            }

            if (state.bufferedSendCount >= maxBufferedSends) {
                state.overloaded = true;
                connection.close(
                        TRY_AGAIN_LATER_CLOSE_CODE,
                        BACKLOG_CLOSE_REASON);
                return SendResult.OVERLOADED;
            }

            connection.send(
                    message);

            if (connection.hasBufferedData()) {
                state.bufferedSendCount++;
            } else {
                state.bufferedSendCount = 0;
            }

            return SendResult.SENT;
        }
    }
}
