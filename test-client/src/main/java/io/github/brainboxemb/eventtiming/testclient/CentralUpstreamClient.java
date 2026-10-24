package io.github.brainboxemb.eventtiming.testclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Full-duplex IF-03 transport used by the Central System Simulator. */
final class CentralUpstreamClient implements AutoCloseable {
    interface Listener {
        void onConnected();

        void onUpstreamData(Instant occurredAt, String payloadJson, String rawJson);

        void onOtherEvent(String eventType, String rawJson);

        void onClosed(int statusCode, String reason);

        void onError(String message);
    }

    record Envelope(
            String eventType,
            Instant occurredAt,
            String payloadJson,
            String rawJson) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient = ClientHttpTransport.shared();

    private WebSocket webSocket;
    private boolean connecting;

    synchronized CompletableFuture<Void> connect(URI endpoint, Listener listener) {
        validateEndpoint(endpoint);
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (webSocket != null || connecting) {
            throw new IllegalStateException(
                    "Central upstream client is already connected or connecting");
        }

        connecting = true;
        ClientListener bridge = new ClientListener(listener);
        CompletableFuture<Void> result = new CompletableFuture<>();

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .buildAsync(endpoint, bridge)
                .whenComplete((socket, error) -> {
                    if (error != null) {
                        synchronized (CentralUpstreamClient.this) {
                            connecting = false;
                        }
                        result.completeExceptionally(error);
                        return;
                    }

                    synchronized (CentralUpstreamClient.this) {
                        webSocket = socket;
                        connecting = false;
                    }
                    result.complete(null);
                });

        return result;
    }

    synchronized boolean isConnected() {
        return webSocket != null && !webSocket.isOutputClosed();
    }

    void send(String upstreamDataJson) {
        if (upstreamDataJson == null || upstreamDataJson.isBlank()) {
            throw new IllegalArgumentException("upstreamDataJson must not be blank");
        }

        WebSocket current;
        synchronized (this) {
            current = webSocket;
        }
        if (current == null || current.isOutputClosed()) {
            throw new IllegalStateException("Central upstream client is not connected");
        }
        current.sendText(upstreamDataJson, true);
    }

    void disconnect() {
        WebSocket current;
        synchronized (this) {
            current = webSocket;
            webSocket = null;
            connecting = false;
        }
        if (current != null && !current.isOutputClosed()) {
            current.sendClose(WebSocket.NORMAL_CLOSURE, "central simulator disconnect");
        }
    }

    static Envelope parseEnvelope(String rawJson) throws IOException {
        JsonNode root = JSON.readTree(rawJson);
        String eventType = requiredText(root, "eventType");
        Instant occurredAt = Instant.parse(requiredText(root, "occurredAt"));
        JsonNode payload = required(root, "payload");
        return new Envelope(
                eventType,
                occurredAt,
                JSON.writeValueAsString(payload),
                rawJson);
    }

    private static JsonNode required(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing IF-03 event field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("IF-03 event field is not text: " + field);
        }
        return value.asText();
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
        }
        connecting = false;
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
                    Envelope envelope = parseEnvelope(rawJson);
                    if ("UPSTREAM_DATA".equals(envelope.eventType())) {
                        listener.onUpstreamData(
                                envelope.occurredAt(),
                                envelope.payloadJson(),
                                envelope.rawJson());
                    } else {
                        listener.onOtherEvent(
                                envelope.eventType(),
                                envelope.rawJson());
                    }
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
        return value == null || value.isBlank() ? cause.toString() : value;
    }
}
