package oss.relay.timing.testclient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteShellClientTest {
    @Test
    void exchangesTextWithLineOrientedRemoteShell() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        StringBuilder output = new StringBuilder();

        try (ServerSocket server = new ServerSocket(0)) {
            Thread serverThread = new Thread(() -> {
                try (Socket socket = server.accept();
                        BufferedReader reader = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.UTF_8));
                        Writer writer = new OutputStreamWriter(
                                socket.getOutputStream(), StandardCharsets.UTF_8)) {
                    writer.write("Remote terminal ready.\nevent-timing> ");
                    writer.flush();
                    String command = reader.readLine();
                    writer.write("received:" + command + "\nevent-timing> ");
                    writer.flush();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();

            try (RemoteShellClient client = new RemoteShellClient()) {
                client.connect("127.0.0.1", server.getLocalPort(), new RemoteShellClient.Listener() {
                    @Override
                    public void onText(String text) {
                        synchronized (output) {
                            output.append(text);
                            if (output.indexOf("received:status") >= 0) {
                                received.countDown();
                            }
                        }
                    }

                    @Override
                    public void onDisconnected() {
                    }

                    @Override
                    public void onError(String message) {
                    }
                });

                client.send("status");
                assertTrue(received.await(2, TimeUnit.SECONDS));
            }
        }

        synchronized (output) {
            assertTrue(output.toString().contains("event-timing> "));
            assertTrue(output.toString().contains("received:status"));
        }
    }
}
