package io.github.brainboxemb.eventtiming.testclient;

import java.net.URI;

/**
 * Instance-scoped connection context for one SI-01 system.
 *
 * <p>The Engineering Client may later hold multiple contexts. Keeping target
 * identity and stateful boundary clients here avoids global UI-owned connection
 * state and gives future scripting the same system abstraction as JavaFX.</p>
 */
final class EngineeringSystemContext implements AutoCloseable {
    private final ClientConfig config;
    private final ApiEventClient eventClient = new ApiEventClient();
    private final RemoteShellClient shellClient = new RemoteShellClient();
    private final LiveLogClient liveLogClient = new LiveLogClient();

    private String host;

    EngineeringSystemContext(ClientConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        this.host = validateHost(config.host());
    }

    String host() {
        return host;
    }

    ClientConfig config() {
        return config;
    }

    ApiClient apiClient() {
        return new ApiClient(apiEndpoint());
    }

    ApiEventClient eventClient() {
        return eventClient;
    }

    RemoteShellClient shellClient() {
        return shellClient;
    }

    LiveLogClient liveLogClient() {
        return liveLogClient;
    }

    URI apiEndpoint() {
        return URI.create("http://" + uriHost(host) + ":" + config.apiHttpPort());
    }

    URI eventEndpoint() {
        return URI.create(
                "ws://" + uriHost(host) + ":" + config.eventPort() + "/api/v1/events");
    }

    boolean changeHost(String value) {
        String next = validateHost(value);
        if (next.equals(host)) {
            return false;
        }
        disconnectStatefulBoundaries();
        host = next;
        return true;
    }

    void disconnectStatefulBoundaries() {
        eventClient.disconnect();
        shellClient.disconnect();
        liveLogClient.disconnect();
    }

    static String validateHost(String value) {
        String host = value == null ? "" : value.trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Target host/IP must not be empty");
        }
        if (host.contains("://") || host.contains("/") || host.contains("\\")) {
            throw new IllegalArgumentException(
                    "Target must be a host or IP address, not a URL");
        }
        return host;
    }

    static String uriHost(String host) {
        return host.indexOf(':') >= 0 && !host.startsWith("[")
                ? "[" + host + "]"
                : host;
    }

    @Override
    public void close() {
        eventClient.close();
        shellClient.close();
        liveLogClient.close();
    }
}
