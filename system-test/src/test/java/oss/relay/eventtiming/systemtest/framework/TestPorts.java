package oss.relay.eventtiming.systemtest.framework;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/** Allocates temporary loopback ports for one black-box test run. */
public final class TestPorts {
    private static final String LOOPBACK = "127.0.0.1";

    private TestPorts() {
    }

    public static int[] reserve(int count) throws IOException {
        ServerSocket[] sockets = new ServerSocket[count];
        int[] ports = new int[count];
        try {
            for (int i = 0; i < count; i++) {
                sockets[i] = new ServerSocket(
                        0,
                        50,
                        InetAddress.getByName(LOOPBACK));
                ports[i] = sockets[i].getLocalPort();
            }
            return ports;
        } finally {
            for (ServerSocket socket : sockets) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // Best-effort release of a test-only reservation.
                    }
                }
            }
        }
    }
}
