package oss.relay.eventtiming.testclient;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Small UTF-8 TCP client for the development remote shell. */
public final class RemoteShellClient implements AutoCloseable {
    public interface Listener {
        void onText(String text);

        void onDisconnected();

        void onError(String message);
    }

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
            throw new IllegalStateException("remote shell is already connected");
        }

        Socket connected = new Socket();
        connected.connect(new InetSocketAddress(host.trim(), port), 3000);
        connected.setTcpNoDelay(true);

        socket = connected;
        writer = new OutputStreamWriter(connected.getOutputStream(), StandardCharsets.UTF_8);
        disconnectRequested = false;

        Thread reader = new Thread(
                () -> readLoop(connected, listener),
                "event-timing-test-client-shell");
        reader.setDaemon(true);
        reader.start();
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized void send(String command) throws IOException {
        if (writer == null || !isConnected()) {
            throw new IllegalStateException("remote shell is not connected");
        }
        writer.write(command == null ? "" : command);
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
        try (Reader reader = new InputStreamReader(
                connected.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[512];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (count > 0) {
                    listener.onText(new String(buffer, 0, count));
                }
            }
        } catch (IOException ex) {
            if (!disconnectRequested) {
                listener.onError(ex.getMessage() == null ? ex.toString() : ex.getMessage());
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
}
