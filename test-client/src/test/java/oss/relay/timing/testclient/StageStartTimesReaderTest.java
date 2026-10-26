package oss.relay.timing.testclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageStartTimesReaderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void unchangedRevisionAvoidsPageRead() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0);
        server.createContext(
                "/api/v1/node/A/stage-start-times",
                exchange -> {
                    assertEquals(null, exchange.getRequestURI().getQuery());
                    respond(exchange, "{\"count\":2,\"revision\":5}");
                });
        server.start();

        StageStartTimesReader.Update update =
                StageStartTimesReader.read(
                        client(),
                        "A",
                        new ApiClient.StageStartInfo(
                                2,
                                5L,
                                "{}"));

        assertFalse(update.replace());
        assertTrue(update.rows().isEmpty());
    }

    @Test
    void changedRevisionReadsBoundedPages() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0);
        server.createContext(
                "/api/v1/node/A/stage-start-times",
                exchange -> {
                    String query = exchange.getRequestURI().getQuery();
                    if (query == null) {
                        respond(exchange, "{\"count\":2,\"revision\":6}");
                    } else {
                        assertEquals("offset=0&limit=128", query);
                        respond(
                                exchange,
                                "{"
                                        + "\"count\":2,"
                                        + "\"revision\":6,"
                                        + "\"nextOffset\":null,"
                                        + "\"startTimes\":["
                                        + "{\"regId\":\"A0001\",\"start\":\"19:00:00\"},"
                                        + "{\"regId\":\"A0002\",\"start\":\"19:00:30\"}"
                                        + "]"
                                        + "}");
                    }
                });
        server.start();

        StageStartTimesReader.Update update =
                StageStartTimesReader.read(
                        client(),
                        "A",
                        new ApiClient.StageStartInfo(
                                2,
                                5L,
                                "{}"));

        assertTrue(update.replace());
        assertEquals(2, update.rows().size());
        assertEquals("A0002", update.rows().get(1).registrationId());
        assertEquals("19:00:30", update.rows().get(1).start());
    }

    private ApiClient client() {
        return new ApiClient(
                URI.create(
                        "http://127.0.0.1:"
                                + server.getAddress().getPort()));
    }

    private static void respond(
            HttpExchange exchange,
            String json)
            throws IOException {
        byte[] bytes =
                json.getBytes(
                        StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set("Content-Type", "application/json");
        exchange.sendResponseHeaders(
                200,
                bytes.length);
        try (var output =
                exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
