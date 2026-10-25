package oss.relay.eventtiming.testclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineeringSystemContextTest {
    @TempDir
    Path temp;

    @Test
    void ownsOneTargetAndBuildsAllPublicEndpointsFromIt() throws Exception {
        ClientConfig config = config("127.0.0.1");
        try (EngineeringSystemContext context = new EngineeringSystemContext(config)) {
            assertEquals("127.0.0.1", context.host());
            assertEquals("http://127.0.0.1:8081", context.apiEndpoint().toString());
            assertEquals("ws://127.0.0.1:8082/api/v1/events", context.eventEndpoint().toString());
            assertEquals(
                    "ws://127.0.0.1:8082/api/v1/events?role=upstream",
                    context.upstreamEventEndpoint().toString());

            assertTrue(context.changeHost("192.0.2.10"));
            assertEquals("192.0.2.10", context.host());
            assertFalse(context.changeHost(" 192.0.2.10 "));
        }
    }

    @Test
    void targetChangeClearsHttpDiagnosticHistory() throws Exception {
        try (EngineeringSystemContext context =
                new EngineeringSystemContext(config("127.0.0.1"))) {
            context.httpTraffic().record(
                    new ApiClient.HttpCall(
                            Instant.parse("2026-10-24T18:00:00Z"),
                            "GET",
                            "/api/v1/status",
                            null,
                            Integer.valueOf(200),
                            "{}",
                            null));
            assertEquals(1, context.httpTraffic().snapshot().size());

            assertTrue(context.changeHost("192.0.2.10"));

            assertTrue(context.httpTraffic().snapshot().isEmpty());
        }
    }

    @Test
    void bracketsIpv6Endpoints() throws Exception {
        try (EngineeringSystemContext context =
                new EngineeringSystemContext(config("2001:db8::10"))) {
            assertEquals("http://[2001:db8::10]:8081", context.apiEndpoint().toString());
            assertEquals(
                    "ws://[2001:db8::10]:8082/api/v1/events",
                    context.eventEndpoint().toString());
            assertEquals(
                    "ws://[2001:db8::10]:8082/api/v1/events?role=upstream",
                    context.upstreamEventEndpoint().toString());
        }
    }

    @Test
    void rejectsUrlsAndEmptyTargets() throws Exception {
        try (EngineeringSystemContext context =
                new EngineeringSystemContext(config("localhost"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> context.changeHost("http://localhost"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> context.changeHost(" "));
            assertEquals("localhost", context.host());
        }
    }

    private ClientConfig config(String host) throws Exception {
        Path file = temp.resolve("client-" + Math.abs(host.hashCode()) + ".properties");
        Files.writeString(
                file,
                "target.host=" + host + System.lineSeparator()
                        + "api.http.port=8081" + System.lineSeparator()
                        + "api.events.port=8082" + System.lineSeparator()
                        + "remoteShell.port=8023" + System.lineSeparator()
                        + "loggingServer.port=8030" + System.lineSeparator()
                        + "client.log.path=" + temp.resolve("client.log") + System.lineSeparator()
                        + "client.log.level=INFO" + System.lineSeparator());
        return ClientConfig.load(file);
    }
}
