package oss.relay.timing.testclient;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HttpTrafficPaneTest {
    private static final Instant TIME =
            Instant.parse("2026-10-24T18:00:00Z");

    @Test
    void filtersMethodAndStatusIndependently() {
        ApiClient.HttpCall call =
                new ApiClient.HttpCall(
                        TIME,
                        "GET",
                        "/api/v1/node/A/logbook?from=1&limit=100",
                        null,
                        Integer.valueOf(200),
                        "{\"count\":100}",
                        null);

        assertTrue(HttpTrafficPane.matches(call, "ALL", "ALL"));
        assertTrue(HttpTrafficPane.matches(call, "GET", "200"));
        assertFalse(HttpTrafficPane.matches(call, "POST", "200"));
        assertFalse(HttpTrafficPane.matches(call, "GET", "409"));
    }

    @Test
    void detailContainsExactRequestAndResponsePayloads() {
        ApiClient.HttpCall call =
                new ApiClient.HttpCall(
                        TIME,
                        "POST",
                        "/api/v1/node/A/open",
                        "{\"locationId\":2}",
                        Integer.valueOf(200),
                        "{\"result\":\"APPLIED\"}",
                        null);

        String detail = HttpTrafficPane.detailText(call);

        assertTrue(detail.contains("POST /api/v1/node/A/open"));
        assertTrue(detail.contains("{\"locationId\":2}"));
        assertTrue(detail.contains("Response 200"));
        assertTrue(detail.contains("{\"result\":\"APPLIED\"}"));
    }
}
