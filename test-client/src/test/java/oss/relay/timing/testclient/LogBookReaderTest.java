package oss.relay.timing.testclient;

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
    void readsCompleteLogBookInStandardBoundedPages() throws Exception {
        List<String> queries = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v1/node/TN-01/logbook",
                exchange -> {
                    String query = exchange.getRequestURI().getQuery();
                    queries.add(query == null ? "" : query);

                    String json;
                    if (query == null) {
                        json = "{\"count\":300,\"first\":1,\"last\":300}";
                    } else {
                        String[] fields = query.split("&");
                        long from =
                                Long.parseLong(
                                        fields[0].substring(
                                                "from=".length()));
                        int limit =
                                Integer.parseInt(
                                        fields[1].substring(
                                                "limit=".length()));
                        long last =
                                Math.min(
                                        300L,
                                        from + limit - 1L);
                        Long next =
                                last < 300L
                                        ? Long.valueOf(last + 1L)
                                        : null;
                        json =
                                "{\"count\":300,\"next\":"
                                        + (next == null ? "null" : next)
                                        + ",\"records\":["
                                        + records(
                                                (int) from,
                                                (int) last)
                                        + "]}";
                    }

                    byte[] body = json.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set(
                            "Content-Type",
                            "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(body);
                    }
                });
        server.start();

        ApiClient api =
                new ApiClient(
                        URI.create(
                                "http://127.0.0.1:"
                                        + server.getAddress().getPort()));

        LogBookReader.Result result =
                LogBookReader.readAll(
                        api,
                        "TN-01");

        assertEquals(300L, result.info().count());
        assertEquals(3, result.pages().size());
        assertEquals(128, result.pages().get(0).records().size());
        assertEquals(128, result.pages().get(1).records().size());
        assertEquals(44, result.pages().get(2).records().size());
        assertEquals(
                300L,
                result.pages().get(2)
                        .records().get(43)
                        .sequenceNumber());
        assertEquals(
                List.of(
                        "",
                        "from=1&limit=128",
                        "from=129&limit=128",
                        "from=257&limit=44"),
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
