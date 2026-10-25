package oss.relay.timing.systemtest.framework;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Minimal client for the supported Remote Shell controlled-shutdown path. */
public final class RemoteShellClient {
    private static final String LOOPBACK = "127.0.0.1";

    private RemoteShellClient() {
    }

    public static String requestStatus(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        IOException lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            Socket socket = new Socket();
            try {
                socket.connect(
                        new InetSocketAddress(LOOPBACK, port),
                        500);
                socket.setSoTimeout(2000);
                String greeting = readUntil(
                        socket.getInputStream(),
                        "event-timing> ");
                if (!greeting.contains("Remote terminal ready")) {
                    throw new IOException(
                            "Unexpected remote terminal greeting: " + greeting);
                }

                PrintWriter writer =
                        new PrintWriter(socket.getOutputStream(), true);
                writer.println("status");
                return readUntil(
                        socket.getInputStream(),
                        "event-timing> ");
            } catch (IOException ex) {
                lastFailure = ex;
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Best-effort test cleanup.
                }
            }
            Thread.sleep(100L);
        }
        throw new IOException(
                "Unable to query remote terminal status",
                lastFailure);
    }

    public static void requestQuit(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5000L;
        IOException lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            Socket socket = new Socket();
            try {
                socket.connect(
                        new InetSocketAddress(LOOPBACK, port),
                        500);
                socket.setSoTimeout(2000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                socket.getInputStream(),
                                StandardCharsets.UTF_8));
                String ready = reader.readLine();
                if (ready == null || !ready.contains("Remote terminal ready")) {
                    throw new IOException(
                            "Unexpected remote terminal greeting: " + ready);
                }
                PrintWriter writer =
                        new PrintWriter(socket.getOutputStream(), true);
                writer.println("quit");
                return;
            } catch (IOException ex) {
                lastFailure = ex;
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Best-effort test cleanup.
                }
            }
            Thread.sleep(100L);
        }
        throw new IOException(
                "Unable to connect to remote terminal for controlled shutdown",
                lastFailure);
    }
    private static String readUntil(
            java.io.InputStream input,
            String marker)
            throws IOException {
        StringBuilder value = new StringBuilder();
        while (value.indexOf(marker) < 0) {
            int next = input.read();
            if (next < 0) {
                break;
            }
            value.append((char) next);
        }
        return value.toString();
    }

}
