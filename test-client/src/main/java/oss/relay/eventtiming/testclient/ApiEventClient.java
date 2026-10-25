package oss.relay.eventtiming.testclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import oss.relay.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import oss.relay.eventtiming.upstreamdata.UpstreamData;
import oss.relay.eventtiming.upstreamdata.UpstreamDataCodec;
import oss.relay.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * IF-03 event client used by the JavaFX Engineering Client.
 *
 * <p>The upstream-role session remains one WebSocket: it carries the normal IF-03 event
 * stream and typed IF-06 UpstreamData in both directions. Semantic Central state stays in
 * CentralSystemSimulator rather than in this transport adapter.</p>
 */
public final class ApiEventClient implements AutoCloseable {
    public interface Listener {
        void onConnected();

        void onEvent(ApiEvent event);

        void onClosed(int statusCode, String reason);

        void onError(String message);
    }

    public interface ApiEvent {
        String eventType();

        Instant occurredAt();

        String rawJson();
    }

    public record StatusEvent(
            String eventType,
            Instant occurredAt,
            ApiClient.StatusResult status,
            String rawJson)
            implements ApiEvent {
    }

    public record TimingDataEvent(
            String eventType,
            Instant occurredAt,
            ApiClient.TimingDataInfo timingData,
            String rawJson)
            implements ApiEvent {
    }

    public record UpstreamDataEvent(
            String eventType,
            Instant occurredAt,
            Instant receivedAt,
            UpstreamData upstreamData,
            String rawPayloadJson,
            String rawJson)
            implements ApiEvent {

        UpstreamDataEvent(
                String eventType,
                Instant occurredAt,
                Instant receivedAt,
                UpstreamData upstreamData,
                String rawJson) {
            this(
                    eventType,
                    occurredAt,
                    receivedAt,
                    upstreamData,
                    ApiEventClient.encodeUpstreamData(upstreamData),
                    rawJson);
        }
    }

    public record UnknownEvent(
            String eventType,
            Instant occurredAt,
            String payloadJson,
            String rawJson)
            implements ApiEvent {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UpstreamDataCodec UPSTREAM_CODEC =
            new DefaultUpstreamDataCodec(new DefaultTimingDataCodec());

    private final HttpClient httpClient = ClientHttpTransport.shared();

    private WebSocket webSocket;
    private boolean connecting;
    private boolean upstreamRole;

    public synchronized CompletableFuture<Void> connect(URI endpoint, Listener listener) {
        validateEndpoint(endpoint);
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (webSocket != null || connecting) {
            throw new IllegalStateException("WebSocket client is already connected or connecting");
        }

        connecting = true;
        upstreamRole = isUpstreamRole(endpoint);
        ClientListener bridge = new ClientListener(listener);
        CompletableFuture<Void> result = new CompletableFuture<>();

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(endpoint, bridge)
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        synchronized (ApiEventClient.this) {
                            connecting = false;
                            upstreamRole = false;
                        }
                        result.completeExceptionally(error);
                        return;
                    }

                    synchronized (ApiEventClient.this) {
                        webSocket = socket;
                        connecting = false;
                    }
                    result.complete(null);
                });

        return result;
    }

    public synchronized boolean isConnected() {
        return webSocket != null && !webSocket.isOutputClosed();
    }

    /** Returns whether the active IF-03 event session has the explicit upstream role. */
    public synchronized boolean isUpstreamRoleConnected() {
        return webSocket != null
                && !webSocket.isOutputClosed()
                && upstreamRole;
    }

    /**
     * Sends one semantic IF-06 value on the active upstream-role session.
     *
     * <p>Encoding belongs to the public shared UpstreamData profile; this client only owns
     * the IF-03 WebSocket transport.</p>
     */
    public CompletableFuture<Void> sendUpstreamData(UpstreamData data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }

        final String encoded = encodeUpstreamData(data);

        WebSocket current;
        synchronized (this) {
            if (!upstreamRole) {
                throw new IllegalStateException(
                        "UpstreamData requires an upstream-role IF-03 session");
            }
            current = webSocket;
        }
        if (current == null || current.isOutputClosed()) {
            throw new IllegalStateException("IF-03 event client is not connected");
        }

        return current.sendText(encoded, true)
                .thenApply(ignored -> (Void) null);
    }

    static String encodeUpstreamData(UpstreamData data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        try {
            return new String(
                    UPSTREAM_CODEC.encode(data),
                    StandardCharsets.UTF_8);
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalArgumentException("Could not encode UpstreamData", ex);
        }
    }

    public void disconnect() {
        WebSocket current;
        synchronized (this) {
            current = webSocket;
            webSocket = null;
            connecting = false;
            upstreamRole = false;
        }
        if (current != null && !current.isOutputClosed()) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "client disconnect");
        }
    }

    static ApiEvent parseEvent(String rawJson) throws IOException {
        return parseEvent(rawJson, null);
    }

    /**
     * Parses one IF-03 event and retains the local receipt time for UPSTREAM_DATA.
     *
     * <p>The single-argument overload is kept for deterministic parser tests and uses
     * occurredAt as the receive-time fallback.</p>
     */
    static ApiEvent parseEvent(
            String rawJson,
            Instant receivedAt) throws IOException {
        JsonNode root = JSON.readTree(rawJson);
        String eventType = ApiClient.requiredText(root, "eventType");
        Instant occurredAt = Instant.parse(
                ApiClient.requiredText(root, "occurredAt"));
        JsonNode payload = ApiClient.required(root, "payload");

        if ("STATUS_SNAPSHOT".equals(eventType)
                || "STATUS_CHANGED".equals(eventType)) {
            return new StatusEvent(
                    eventType,
                    occurredAt,
                    ApiClient.parseStatus(JSON.writeValueAsString(payload)),
                    rawJson);
        }

        if ("TIMING_DATA_COMMITTED".equals(eventType)) {
            return new TimingDataEvent(
                    eventType,
                    occurredAt,
                    ApiClient.parseTimingData(payload),
                    rawJson);
        }

        if ("UPSTREAM_DATA".equals(eventType)) {
            try {
                String rawPayloadJson =
                        JSON.writeValueAsString(payload);
                UpstreamData decoded = UPSTREAM_CODEC.decode(
                        rawPayloadJson.getBytes(StandardCharsets.UTF_8));
                return new UpstreamDataEvent(
                        eventType,
                        occurredAt,
                        receivedAt == null ? occurredAt : receivedAt,
                        decoded,
                        rawPayloadJson,
                        rawJson);
            } catch (UpstreamDataCodec.CodecException ex) {
                throw new IllegalArgumentException(
                        "Invalid UPSTREAM_DATA event payload",
                        ex);
            }
        }

        return new UnknownEvent(
                eventType,
                occurredAt,
                JSON.writeValueAsString(payload),
                rawJson);
    }

    static boolean isUpstreamRole(URI endpoint) {
        String query = endpoint.getQuery();
        if (query == null) {
            return false;
        }
        for (String parameter : query.split("&")) {
            if ("role=upstream".equals(parameter)) {
                return true;
            }
        }
        return false;
    }

    private static void validateEndpoint(URI endpoint) {
        if (endpoint == null || endpoint.getScheme() == null || endpoint.getHost() == null) {
            throw new IllegalArgumentException("endpoint must be an absolute WebSocket URI");
        }
        if (!"ws".equalsIgnoreCase(endpoint.getScheme())
                && !"wss".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("endpoint scheme must be ws or wss");
        }
    }

    private synchronized void disconnected(WebSocket socket) {
        if (webSocket == socket) {
            webSocket = null;
            upstreamRole = false;
            connecting = false;
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    private final class ClientListener implements WebSocket.Listener {
        private final Listener listener;
        private final StringBuilder text = new StringBuilder();

        private ClientListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
            listener.onConnected();
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket socket,
                CharSequence data,
                boolean last) {
            text.append(data);
            if (last) {
                String rawJson = text.toString();
                text.setLength(0);
                try {
                    Instant receivedAt = Instant.now();
                    listener.onEvent(parseEvent(rawJson, receivedAt));
                } catch (Exception ex) {
                    listener.onError(message(ex));
                }
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket socket,
                int statusCode,
                String reason) {
            disconnected(socket);
            listener.onClosed(statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            disconnected(socket);
            listener.onError(message(error));
        }
    }

    private static String message(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String value = cause.getMessage();
        return value == null || value.trim().isEmpty() ? cause.toString() : value;
    }
}
