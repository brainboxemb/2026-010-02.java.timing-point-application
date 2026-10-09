package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell;

import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.PresentationGatewayFixture;

import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteShellServerTest {

    @Test
    public void acceptsFirstCommandBeforeClientReadsBanner() throws Exception {
        PresentationGatewayFixture fixture = new PresentationGatewayFixture(identity());
        RemoteShellServer server =
                new RemoteShellServer("127.0.0.1", 0, fixture.handler(), () -> { });
        server.start();

        try (Socket client = connect(server.boundPort())) {
            Writer writer =
                    new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8);
            writer.write("help\n");
            writer.flush();

            String response = readUntil(client.getInputStream(), "event-timing> ");
            response += readUntil(client.getInputStream(), "event-timing> ");

            assertTrue(response.contains("Remote terminal ready."));
            assertTrue(response.contains("Commands:"));
            assertTrue(response.contains("help                         Show available commands"));
            assertFalse(response.contains("Unknown command"));
        } finally {
            server.close();
            fixture.close();
        }
    }

    @Test
    public void reconnectsAfterDisconnectAndUsesSharedShutdownCommand() throws Exception {
        AtomicBoolean shutdown = new AtomicBoolean(false);
        PresentationGatewayFixture fixture = new PresentationGatewayFixture(identity());
        RemoteShellServer server =
                new RemoteShellServer("127.0.0.1", 0, fixture.handler(), () -> shutdown.set(true));
        server.start();

        try {
            try (Socket first = connect(server.boundPort())) {
                String banner = readUntil(first.getInputStream(), "event-timing> ");
                assertTrue(banner.contains("Remote terminal ready."));
            }

            try (Socket second = connect(server.boundPort())) {
                String banner = readUntil(second.getInputStream(), "event-timing> ");
                assertTrue(banner.contains("Remote terminal ready."));
                assertFalse(shutdown.get());

                Writer writer =
                        new OutputStreamWriter(second.getOutputStream(), StandardCharsets.UTF_8);
                writer.write(
                        "version\n"
                                + "node\n"
                                + "open:A 24\n"
                                + "status:A\n"
                                + "node\n"
                                + "open 24\n"
                                + "auto-reg:A N0002 2026-10-01T12:00:00Z\n"
                                + "auto-reg N0003 2026-10-01T12:00:00Z\n"
                                + "status:B\n"
                                + "open: 25\n"
                                + "quit:A\n"
                                + "config tag-processing set "
                                + "quietTimeoutMillis=300\n"
                                + "close\n"
                                + "status\n"
                                + "quit\n");
                writer.flush();

                String response = readToEnd(second.getInputStream());
                assertTrue(response.contains("Version      : test-version"));
                assertTrue(response.contains("Open: OPENED"));
                assertTrue(response.contains("TimingNode: A"));
                assertTrue(response.contains("Unknown TimingNode B"));
                assertTrue(response.contains("Invalid NodeId: "));
                assertTrue(response.contains("Command does not accept a NodeId: quit"));
                assertTrue(response.contains(
                        "Automatic registration: COMMITTED seq=2"));
                assertTrue(response.contains(
                        "Tag processing update: APPLIED"));
                assertTrue(response.contains("Close: CLOSED"));
                assertTrue(response.contains("Timing node"));
                assertTrue(response.contains("Id        : A"));
                assertTrue(response.contains("State     : CLOSED"));
                assertTrue(response.contains("Stopping application."));
            }

            assertTrue(shutdown.get());
        } finally {
            server.close();
            fixture.close();
        }
    }

    @Test
    public void multiNodeDefaultsToStrictAddressingAndShowsSelectionInPrompt()
            throws Exception {
        PresentationGatewayFixture fixture =
                new PresentationGatewayFixture(identity(), "A", "B");
        RemoteShellServer server =
                new RemoteShellServer(
                        "127.0.0.1",
                        0,
                        fixture.handler(),
                        () -> { });
        server.start();

        try (Socket client = connect(server.boundPort())) {
            String banner =
                    readUntil(
                            client.getInputStream(),
                            "event-timing[A]> ");
            assertTrue(banner.contains("Remote terminal ready."));

            Writer writer =
                    new OutputStreamWriter(
                            client.getOutputStream(),
                            StandardCharsets.UTF_8);
            writer.write(
                    "node-mode\n"
                            + "open 1\n"
                            + "open:B 2\n"
                            + "node\n"
                            + "status:B\n"
                            + "node B\n"
                            + "close\n"
                            + "node-mode selected\n"
                            + "close\n"
                            + "status\n"
                            + "node-mode strict\n"
                            + "status\n"
                            + "status:A\n"
                            + "quit\n");
            writer.flush();

            String response = readToEnd(client.getInputStream());
            assertTrue(response.contains("Node addressing mode: STRICT"));
            assertTrue(response.contains(
                    "Explicit NodeId required in multi-node strict mode. "
                            + "Use open:<NodeId>."));
            assertTrue(response.contains("Open: OPENED"));
            assertTrue(response.contains("TimingNode: A"));
            assertTrue(response.contains("Available: A B"));
            assertTrue(response.contains("Id        : B"));
            assertTrue(response.contains("Location  : 2"));
            assertTrue(response.contains("event-timing[B]> "));
            assertTrue(response.contains(
                    "Explicit NodeId required in multi-node strict mode. "
                            + "Use close:<NodeId>."));
            assertTrue(response.contains("Node addressing mode: SELECTED"));
            assertTrue(response.contains("Close: CLOSED"));
            assertTrue(response.contains(
                    "Explicit NodeId required in multi-node strict mode. "
                            + "Use status:<NodeId>."));
        } finally {
            server.close();
            fixture.close();
        }
    }

    private static Socket connect(int port) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
        socket.setSoTimeout(2000);
        return socket;
    }

    private static String readUntil(InputStream input, String marker) throws Exception {
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

    private static String readToEnd(InputStream input) throws Exception {
        StringBuilder value = new StringBuilder();
        int next;
        while ((next = input.read()) >= 0) {
            value.append((char) next);
        }
        return value.toString();
    }

    private static BuildIdentity identity() {
        return BuildIdentity.firstApiVersion(
                "event-timing-app",
                "test-version",
                "abc123def456",
                "feature/test",
                "local",
                false);
    }
}
