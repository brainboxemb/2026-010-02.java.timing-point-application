package io.github.brainboxemb.eventtiming.testclient;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogBookReaderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsCompleteLogBookInBoundedPages() throws Exception {
        List<String> queries = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/node/TN-01/logbook",
                exchange -> {
                    String query = exchange.getRequestURI().getQuery();
                    queries.add(query == null ? "" : query);
                    String json;
                    if (query == null) {
                        json = "{\"count\":1001,\"first\":1,\"last\":1001}";
                    } else if ("from=1&limit=1000".equals(query)) {
                        json = "{\"count\":1001,\"next\":1001,\"records\":["
                                + records(1, 1000) + "]}";
                    } else {
                        json = "{\"count\":1001,\"next\":null,\"records\":["
                                + record(1001) + "]}";
                    }
                    byte[] body = json.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();

        ApiClient api = new ApiClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));

        LogBookReader.Result result = LogBookReader.readAll(api, "TN-01");

        assertEquals(1001L, result.info().count());
        assertEquals(2, result.pages().size());
        assertEquals(1000, result.pages().get(0).records().size());
        assertEquals(1001L, result.pages().get(1).records().get(0).sequenceNumber());
        assertEquals(
                List.of("", "from=1&limit=1000", "from=1001&limit=1"),
                queries);
    }

    private static String records(int first, int last) {
        StringBuilder result = new StringBuilder();
        for (int sequence = first; sequence <= last; sequence++) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(record(sequence));
        }
        return result.toString();
    }

    private static String record(int sequence) {
        return "{"
                + "\"v\":1,"
                + "\"nodeId\":\"TN-01\","
                + "\"seqNr\":" + sequence + ","
                + "\"locId\":24,"
                + "\"recType\":\"AUTO_REG\","
                + "\"time\":\"2026-10-01T12:00:00Z\","
                + "\"regId\":\"A0001\","
                + "\"code\":[\"ADD\"]"
                + "}";
    }
}
