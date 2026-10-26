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

class RegistrationProjectionReaderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void appendOnlyGrowthReadsOnlyMissingTail() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress(
                        "127.0.0.1",
                        0),
                0);
        server.createContext(
                "/api/v1/node/A/registrations",
                exchange -> {
                    String query =
                            exchange.getRequestURI()
                                    .getQuery();
                    if (query == null) {
                        respond(
                                exchange,
                                "{"
                                        + "\"locId\":24,"
                                        + "\"count\":3,"
                                        + "\"uniqueCount\":3,"
                                        + "\"lastMutationSeq\":3,"
                                        + "\"lastRevokeSeq\":0"
                                        + "}");
                    } else {
                        assertEquals(
                                "after=2&limit=128",
                                query);
                        respond(
                                exchange,
                                "{"
                                        + "\"locId\":24,"
                                        + "\"count\":3,"
                                        + "\"uniqueCount\":3,"
                                        + "\"lastMutationSeq\":3,"
                                        + "\"lastRevokeSeq\":0,"
                                        + "\"nextAfter\":null,"
                                        + "\"registrations\":[{"
                                        + "\"seqNr\":3,"
                                        + "\"regId\":\"A0003\","
                                        + "\"time\":\"2026-10-01T12:00:03Z\","
                                        + "\"valid\":true"
                                        + "}]"
                                        + "}");
                    }
                });
        server.start();

        RegistrationProjectionReader.Update update =
                RegistrationProjectionReader.read(
                        client(),
                        "A",
                        info(
                                24,
                                2,
                                2,
                                2L,
                                0L),
                        2,
                        2L);

        assertFalse(
                update.replace());
        assertEquals(
                1,
                update.rows().size());
        assertEquals(
                3L,
                update.rows()
                        .get(0)
                        .sequenceNumber());
    }

    @Test
    void explicitFullReadRefreshesDerivedRuntimeWithoutMetadataChange() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress(
                        "127.0.0.1",
                        0),
                0);
        server.createContext(
                "/api/v1/node/A/registrations",
                exchange -> {
                    String query =
                            exchange.getRequestURI()
                                    .getQuery();
                    if (query == null) {
                        respond(
                                exchange,
                                "{"
                                        + "\"locId\":24,"
                                        + "\"count\":1,"
                                        + "\"uniqueCount\":1,"
                                        + "\"lastMutationSeq\":2,"
                                        + "\"lastRevokeSeq\":0"
                                        + "}");
                    } else {
                        assertEquals(
                                "after=0&limit=128",
                                query);
                        respond(
                                exchange,
                                "{"
                                        + "\"locId\":24,"
                                        + "\"count\":1,"
                                        + "\"uniqueCount\":1,"
                                        + "\"lastMutationSeq\":2,"
                                        + "\"lastRevokeSeq\":0,"
                                        + "\"nextAfter\":null,"
                                        + "\"registrations\":[{"
                                        + "\"seqNr\":2,"
                                        + "\"regId\":\"A0001\","
                                        + "\"time\":\"2026-10-01T12:00:01Z\","
                                        + "\"elapsedMillis\":12340,"
                                        + "\"valid\":true"
                                        + "}]"
                                        + "}");
                    }
                });
        server.start();

        RegistrationProjectionReader.Update update =
                RegistrationProjectionReader.readFull(
                        client(),
                        "A");

        assertTrue(update.replace());
        assertEquals(
                Long.valueOf(12340L),
                update.rows().get(0).elapsedMillis());
    }

    @Test
    void revokeForcesFullProjectionRead() throws Exception {
        server = HttpServer.create(
                new InetSocketAddress(
                        "127.0.0.1",
                        0),
                0);
        server.createContext(
                "/api/v1/node/A/registrations",
                exchange -> {
                    String query =
                            exchange.getRequestURI()
                                    .getQuery();
                    if (query == null) {
                        respond(
                                exchange,
                                "{"
                                        + "\"locId\":24,"
                                        + "\"count\":2,"
                                        + "\"uniqueCount\":1,"
                                        + "\"lastMutationSeq\":4,"
                                        + "\"lastRevokeSeq\":4"
                                        + "}");
                    } else {
                        assertEquals(
                                "after=0&limit=128",
                                query);
                        respond(
                                exchange,
                                "{"
                                        + "\"locId\":24,"
                                        + "\"count\":2,"
                                        + "\"uniqueCount\":1,"
                                        + "\"lastMutationSeq\":4,"
                                        + "\"lastRevokeSeq\":4,"
                                        + "\"nextAfter\":null,"
                                        + "\"registrations\":["
                                        + "{"
                                        + "\"seqNr\":2,"
                                        + "\"regId\":\"A0001\","
                                        + "\"time\":\"2026-10-01T12:00:01Z\","
                                        + "\"valid\":false"
                                        + "},"
                                        + "{"
                                        + "\"seqNr\":3,"
                                        + "\"regId\":\"A0002\","
                                        + "\"time\":\"2026-10-01T12:00:02Z\","
                                        + "\"valid\":true"
                                        + "}"
                                        + "]"
                                        + "}");
                    }
                });
        server.start();

        RegistrationProjectionReader.Update update =
                RegistrationProjectionReader.read(
                        client(),
                        "A",
                        info(
                                24,
                                2,
                                2,
                                3L,
                                0L),
                        2,
                        3L);

        assertTrue(
                update.replace());
        assertEquals(
                2,
                update.rows().size());
        assertFalse(
                update.rows()
                        .get(0)
                        .valid());
        assertTrue(
                update.rows()
                        .get(1)
                        .valid());
    }

    private ApiClient client() {
        return new ApiClient(
                URI.create(
                        "http://127.0.0.1:"
                                + server.getAddress()
                                        .getPort()));
    }

    private static ApiClient.RegistrationProjectionInfo info(
            int locationId,
            int count,
            int uniqueCount,
            long lastMutation,
            long lastRevoke) {
        return new ApiClient.RegistrationProjectionInfo(
                locationId,
                count,
                uniqueCount,
                lastMutation,
                lastRevoke,
                0L,
                "{}");
    }

    private static void respond(
            HttpExchange exchange,
            String json)
            throws IOException {
        byte[] bytes =
                json.getBytes(
                        StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        "application/json");
        exchange.sendResponseHeaders(
                200,
                bytes.length);
        try (var output =
                exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
