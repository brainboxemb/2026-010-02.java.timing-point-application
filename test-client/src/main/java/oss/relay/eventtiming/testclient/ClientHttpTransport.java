package oss.relay.eventtiming.testclient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Shared JDK HTTP transport for the standalone Development Client.
 *
 * <p>The JDK HttpClient owns selector/worker threads. Reusing one process-wide
 * transport avoids creating another HttpClient thread group for every button
 * action while still allowing endpoint-bound ApiClient wrappers.</p>
 */
final class ClientHttpTransport {
    private static final HttpClient SHARED = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private ClientHttpTransport() {
    }

    static HttpClient shared() {
        return SHARED;
    }
}
