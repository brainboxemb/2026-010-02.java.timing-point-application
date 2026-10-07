package io.github.brainboxemb.eventtiming.testclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ApiClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsCompactVersionStatusAndCapabilities() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/version", exchange -> respond(exchange, 200,
                "{"
                        + "\"application\":\"event-timing-app\","
                        + "\"version\":\"0.2.3-SNAPSHOT\","
                        + "\"revision\":\"abc123\","
                        + "\"sourceRef\":\"feature/test\","
                        + "\"buildOrigin\":\"local\","
                        + "\"dirty\":false,"
                        + "\"apiVersion\":\"1\""
                        + "}"));
        server.createContext("/api/v1/status", exchange -> respond(exchange, 200,
                "{"
                        + "\"nodes\":["
                        + "{"
                        + "\"id\":\"TN-01\","
                        + "\"locationId\":24,"
                        + "\"state\":\"OPEN\""
                        + "},"
                        + "{"
                        + "\"id\":\"timing-node-02\","
                        + "\"locationId\":null,"
                        + "\"state\":\"CLOSED\""
                        + "}"
                        + "],"
                        + "\"problems\":[{"
                        + "\"code\":\"TIMING_DATA_RECOVERY_FAILED\","
                        + "\"severity\":\"ERROR\","
                        + "\"nodeId\":\"TN-01\","
                        + "\"message\":\"recovery failed\""
                        + "}]"
                        + "}"));
        server.createContext("/api/v1/capabilities", exchange -> respond(exchange, 200,
                "{"
                        + "\"capabilities\":[{"
                        + "\"id\":\"DIRECT_REGISTRATION_SIMULATION\","
                        + "\"supported\":true,"
                        + "\"enabled\":true"
                        + "},{"
                        + "\"id\":\"TAG_SCENARIO_SIMULATION\","
                        + "\"supported\":true,"
                        + "\"enabled\":true"
                        + "}]"
                        + "}"));
        server.start();

        ApiClient client = client();

        var version = client.getVersion();
        assertEquals("event-timing-app", version.build().application());
        assertEquals("0.2.3-SNAPSHOT", version.build().version());
        assertEquals("feature/test", version.build().sourceRef());
        assertFalse(version.build().dirty());

        var status = client.getStatus();
        assertEquals(2, status.nodes().size());
        assertEquals("TN-01", status.nodes().get(0).id());
        assertEquals(24, status.nodes().get(0).locationId());
        assertEquals("OPEN", status.nodes().get(0).state());
        assertEquals("timing-node-02", status.nodes().get(1).id());
        assertNull(status.nodes().get(1).locationId());
        assertEquals("CLOSED", status.nodes().get(1).state());
        assertEquals(1, status.problems().size());
        assertEquals(
                "TIMING_DATA_RECOVERY_FAILED",
                status.problems().get(0).code());
        assertEquals("ERROR", status.problems().get(0).severity());
        assertEquals("TN-01", status.problems().get(0).nodeId());
        assertEquals("recovery failed", status.problems().get(0).message());

        var capabilities = client.getCapabilities();
        assertTrue(capabilities.enabled("DIRECT_REGISTRATION_SIMULATION"));
        assertTrue(capabilities.enabled("TAG_SCENARIO_SIMULATION"));
        assertFalse(capabilities.enabled("UNKNOWN"));
    }

    @Test
    void addressesNodeControlsAndBoundedLogBook() throws Exception {
        List<Request> requests = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();

            if (path.endsWith("/open")) {
                respond(exchange, 200, "{\"result\":\"OPENED\"}");
            } else if (path.endsWith("/close")) {
                respond(exchange, 200, "{\"result\":\"CLOSED\"}");
            } else if (path.endsWith("/auto-reg")) {
                respond(exchange, 200, "{\"seq\":2}");
            } else if (path.endsWith("/simulation/registration")) {
                respond(exchange, 200, "{\"result\":\"ACCEPTED\"}");
            } else if (path.endsWith("/registration/manual")) {
                respond(exchange, 200, "{\"seq\":3}");
            } else if (path.endsWith("/registration/revoke")) {
                respond(exchange, 200, "{\"seq\":3}");
            } else if (path.endsWith("/logbook") && query == null) {
                respond(exchange, 200, "{\"count\":2,\"first\":1,\"last\":2}");
            } else if (path.endsWith("/logbook") && "from=1&limit=100".equals(query)) {
                respond(exchange, 200,
                        "{\"count\":2,\"next\":null,\"records\":["
                                + nodeInfo(1, "OPEN")
                                + ","
                                + timingData(2, "N0002")
                                + "]}");
            } else if (path.endsWith("/logbook") && "last=1".equals(query)) {
                respond(exchange, 200,
                        "{\"count\":2,\"next\":null,\"records\":["
                                + timingData(2, "N0002")
                                + "]}");
            } else {
                respond(exchange, 404,
                        "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"missing\"}}");
            }
        });
        server.start();

        ApiClient client = client();
        assertEquals("OPENED", client.open("TN-01", 24).result());
        assertEquals(2L, client.autoReg(
                "TN-01",
                "N0002",
                "2026-10-01T12:00:04.00Z").seq());
        assertEquals(
                "ACCEPTED",
                client.simulateRegistration(
                        "TN-01",
                        "N0042",
                        "normal")
                        .result());
        assertEquals(
                3L,
                client.manualRegistration(
                        "TN-01",
                        "N0003",
                        "2026-10-01T12:00:05.25Z",
                        "AUTO")
                        .seq());

        var info = client.getLogBookInfo("TN-01");
        assertEquals(2L, info.count());
        assertEquals(1L, info.first());
        assertEquals(2L, info.last());

        var page = client.getLogBookFrom("TN-01", 1L, 100);
        assertEquals(2L, page.count());
        assertNull(page.next());
        assertEquals(2, page.records().size());
        assertNull(page.records().get(0).registrationId());
        assertEquals("NODE_INFO", page.records().get(0).recordType());
        assertEquals(List.of("OPEN"), page.records().get(0).codes());
        assertTrue(page.records().get(0).rawJson().contains("\"seqNr\":1"));
        assertEquals("N0002", page.records().get(1).registrationId());
        assertEquals(
                new ApiClient.TimingDataKey("TN-01", 2L),
                page.records().get(1).key());

        var latest = client.getLogBookLast("TN-01", 1);
        assertEquals(1, latest.records().size());
        assertEquals(2L, latest.records().get(0).sequenceNumber());

        assertEquals(
                3L,
                client.revokeRegistration(
                        "TN-01",
                        page.records().get(1))
                        .seq());

        assertEquals("CLOSED", client.close("TN-01").result());

        assertEquals("POST", requests.get(0).method());
        assertEquals("/api/v1/node/TN-01/open", requests.get(0).uri());
        assertTrue(requests.get(0).body().contains("\"locationId\":24"));

        assertEquals("POST", requests.get(1).method());
        assertEquals(
                "/api/v1/dev/node/TN-01/auto-reg",
                requests.get(1).uri());
        assertTrue(requests.get(1).body().contains("\"id\":\"N0002\""));
        assertTrue(requests.get(1).body().contains(
                "\"time\":\"2026-10-01T12:00:04.00Z\""));

        Request simulation = requests.stream()
                .filter(request -> request.uri().endsWith("/simulation/registration"))
                .findFirst()
                .orElseThrow();
        assertEquals("POST", simulation.method());
        assertEquals(
                "/api/v1/dev/node/TN-01/simulation/registration",
                simulation.uri());
        assertTrue(simulation.body().contains("\"regId\":\"N0042\""));
        assertTrue(simulation.body().contains("\"profile\":\"normal\""));

        Request manual = requests.stream()
                .filter(request -> request.uri().endsWith("/registration/manual"))
                .findFirst()
                .orElseThrow();
        assertEquals("POST", manual.method());
        assertTrue(manual.body().contains("\"regId\":\"N0003\""));
        assertTrue(manual.body().contains(
                "\"time\":\"2026-10-01T12:00:05.25Z\""));
        assertTrue(manual.body().contains("\"timeSource\":\"AUTO\""));

        Request revoke = requests.stream()
                .filter(request -> request.uri().endsWith("/registration/revoke"))
                .findFirst()
                .orElseThrow();
        assertEquals("POST", revoke.method());
        assertTrue(revoke.body().contains("\"recordType\":\"AUTO_REG\""));
        assertTrue(revoke.body().contains("\"locationId\":24"));
        assertTrue(revoke.body().contains("\"regId\":\"N0002\""));
        assertTrue(revoke.body().contains(
                "\"time\":\"2026-10-01T12:00:00Z\""));
    }

    @Test
    void exposesStructuredIf03Errors() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, 409,
                "{"
                        + "\"error\":{"
                        + "\"code\":\"NODE_NOT_OPEN\","
                        + "\"message\":\"Accepted registration requires an OPEN TimingNode\""
                        + "}"
                        + "}"));
        server.start();

        ApiClient.ApiException error = assertThrows(
                ApiClient.ApiException.class,
                () -> client().autoReg(
                        "TN-01",
                        "N0001",
                        "2026-10-01T12:00:00.000000000Z"));

        assertEquals(409, error.statusCode());
        assertEquals("NODE_NOT_OPEN", error.code());
        assertTrue(error.getMessage().contains("OPEN TimingNode"));
    }

    @Test
    void validatesLogBookBoundsLocally() {
        ApiClient client = new ApiClient(URI.create("http://127.0.0.1:8081"));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.getLogBookFrom("TN-01", 0L, 100));
        assertThrows(
                IllegalArgumentException.class,
                () -> client.getLogBookLast("TN-01", 1001));
    }

    private ApiClient client() {
        return new ApiClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private static String nodeInfo(long sequence, String code) {
        return "{"
                + "\"v\":1,"
                + "\"nodeId\":\"TN-01\","
                + "\"seqNr\":" + sequence + ","
                + "\"locId\":24,"
                + "\"recType\":\"NODE_INFO\","
                + "\"time\":\"2026-10-01T11:59:59Z\","
                + "\"code\":[\"" + code + "\"],"
                + "\"recTime\":\"2026-10-01T11:59:59.125Z\""
                + "}";
    }

    private static String timingData(long sequence, String registrationId) {
        return "{"
                + "\"v\":1,"
                + "\"nodeId\":\"TN-01\","
                + "\"seqNr\":" + sequence + ","
                + "\"locId\":24,"
                + "\"recType\":\"AUTO_REG\","
                + "\"time\":\"2026-10-01T12:00:00Z\","
                + "\"regId\":\"" + registrationId + "\","
                + "\"code\":[\"ADD\"],"
                + "\"recTime\":\"2026-10-01T12:00:00.125Z\""
                + "}";
    }

    private static void respond(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private record Request(String method, String uri, String body) {
    }
}
