package oss.relay.timing.testclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Independent client for the SI-01 engineering live-log diagnostics listener. */
public final class LiveLogClient implements AutoCloseable {
    public interface Listener {
        void onConnected();

        void onLog(LogEntry entry);

        void onLevel(String level);

        void onDisconnected();

        void onError(String message);
    }

    public record LogEntry(
            Instant occurredAt,
            String level,
            String logger,
            String source,
            String message,
            String formatted,
            String thrown,
            String rawJson) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private Socket socket;
    private Writer writer;
    private volatile boolean disconnectRequested;

    public synchronized void connect(String host, int port, Listener listener) throws IOException {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (socket != null) {
            throw new IllegalStateException("live logging is already connected");
        }

        Socket connected = new Socket();
        connected.connect(new InetSocketAddress(host.trim(), port), 3000);
        connected.setTcpNoDelay(true);
        socket = connected;
        writer = new OutputStreamWriter(connected.getOutputStream(), StandardCharsets.UTF_8);
        disconnectRequested = false;

        Thread reader = new Thread(
                () -> readLoop(connected, listener),
                "event-timing-test-client-logs");
        reader.setDaemon(true);
        reader.start();
        listener.onConnected();
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized void requestLevel() throws IOException {
        send("GET_LEVEL");
    }

    public synchronized void setLevel(String level) throws IOException {
        if (level == null || level.trim().isEmpty()) {
            throw new IllegalArgumentException("level must not be blank");
        }
        send("SET_LEVEL " + level.trim().toUpperCase());
    }

    private void send(String command) throws IOException {
        if (writer == null || !isConnected()) {
            throw new IllegalStateException("live logging is not connected");
        }
        writer.write(command);
        writer.write("\n");
        writer.flush();
    }

    public synchronized void disconnect() {
        disconnectRequested = true;
        closeSocket(socket);
        socket = null;
        writer = null;
    }

    private void readLoop(Socket connected, Listener listener) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connected.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                dispatch(line, listener);
            }
        } catch (IOException ex) {
            if (!disconnectRequested) {
                listener.onError(message(ex));
            }
        } finally {
            synchronized (this) {
                if (socket == connected) {
                    socket = null;
                    writer = null;
                }
            }
            closeSocket(connected);
            listener.onDisconnected();
        }
    }

    static void dispatch(String rawJson, Listener listener) throws IOException {
        JsonNode root = JSON.readTree(rawJson);
        String type = requiredText(root, "type");
        if ("log".equals(type)) {
            JsonNode thrown = root.get("thrown");
            listener.onLog(new LogEntry(
                    Instant.parse(requiredText(root, "occurredAt")),
                    requiredText(root, "level"),
                    requiredText(root, "logger"),
                    requiredText(root, "source"),
                    requiredText(root, "message"),
                    requiredText(root, "formatted"),
                    thrown == null || thrown.isNull() ? null : thrown.asText(),
                    rawJson));
            return;
        }
        if ("level".equals(type)) {
            listener.onLevel(requiredText(root, "level"));
            return;
        }
        if ("error".equals(type)) {
            listener.onError(requiredText(root, "message"));
            return;
        }
        listener.onError("Unknown live-log message type: " + type);
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("Missing live-log text field: " + field);
        }
        return value.asText();
    }

    @Override
    public void close() {
        disconnect();
    }

    private static void closeSocket(Socket value) {
        if (value == null) {
            return;
        }
        try {
            value.close();
        } catch (IOException ignored) {
            // Best-effort client cleanup.
        }
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.toString() : value;
    }
}
