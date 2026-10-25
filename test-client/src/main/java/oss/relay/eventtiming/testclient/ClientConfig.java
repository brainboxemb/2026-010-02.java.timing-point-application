package oss.relay.eventtiming.testclient;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Engineering Client-local endpoint and presentation configuration. */
final class ClientConfig {
    private static final String CONFIG_FILE = "engineering-client.properties";

    private final String host;
    private final int apiHttpPort;
    private final int eventPort;
    private final int shellPort;
    private final int loggingServerPort;
    private final List<Integer> centralLocationIds;
    private final Path clientLogPath;
    private final String clientLogLevel;

    private ClientConfig(
            String host,
            int apiHttpPort,
            int eventPort,
            int shellPort,
            int loggingServerPort,
            List<Integer> centralLocationIds,
            Path clientLogPath,
            String clientLogLevel) {
        this.host = host;
        this.apiHttpPort = apiHttpPort;
        this.eventPort = eventPort;
        this.shellPort = shellPort;
        this.loggingServerPort = loggingServerPort;
        this.centralLocationIds = List.copyOf(centralLocationIds);
        this.clientLogPath = clientLogPath;
        this.clientLogLevel = clientLogLevel;
    }

    static Path defaultPath() {
        return defaultPath(Path.of("").toAbsolutePath());
    }

    static Path defaultPath(Path workingDirectory) {
        if (workingDirectory == null) {
            throw new IllegalArgumentException("workingDirectory must not be null");
        }

        Path repositoryLaunch = workingDirectory
                .resolve("config")
                .resolve(CONFIG_FILE)
                .normalize();
        if (Files.isRegularFile(repositoryLaunch)) {
            return repositoryLaunch;
        }

        Path moduleLaunch = workingDirectory
                .resolve("..")
                .resolve("config")
                .resolve(CONFIG_FILE)
                .normalize();
        if (Files.isRegularFile(moduleLaunch)) {
            return moduleLaunch;
        }

        return repositoryLaunch;
    }

    static ClientConfig load(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }

        String level = required(properties, "client.log.level").toUpperCase(Locale.ROOT);
        if (!level.equals("TRACE")
                && !level.equals("DEBUG")
                && !level.equals("INFO")
                && !level.equals("WARN")
                && !level.equals("ERROR")) {
            throw new IllegalArgumentException("Unsupported client.log.level: " + level);
        }

        return new ClientConfig(
                required(properties, "target.host"),
                port(properties, "api.http.port"),
                port(properties, "api.events.port"),
                port(properties, "remoteShell.port"),
                port(properties, "loggingServer.port"),
                locationIds(properties.getProperty("central.locationIds", "")),
                Path.of(required(properties, "client.log.path")),
                level);
    }

    String host() {
        return host;
    }

    int apiHttpPort() {
        return apiHttpPort;
    }

    int eventPort() {
        return eventPort;
    }

    int shellPort() {
        return shellPort;
    }

    int loggingServerPort() {
        return loggingServerPort;
    }

    List<Integer> centralLocationIds() {
        return centralLocationIds;
    }

    Path clientLogPath() {
        return clientLogPath;
    }

    String clientLogLevel() {
        return clientLogLevel;
    }

    URI apiEndpoint() {
        return URI.create("http://" + host + ":" + apiHttpPort);
    }

    URI eventEndpoint() {
        return URI.create("ws://" + host + ":" + eventPort + "/api/v1/events");
    }

    private static List<Integer> locationIds(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }

        List<Integer> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(
                        "central.locationIds contains an empty value");
            }
            final int locationId;
            try {
                locationId = Integer.parseInt(value);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "central.locationIds must contain integers", ex);
            }
            if (locationId <= 0) {
                throw new IllegalArgumentException(
                        "central.locationIds must contain positive values");
            }
            if (!result.contains(locationId)) {
                result.add(locationId);
            }
        }
        return List.copyOf(result);
    }

    private static int port(Properties properties, String key) {
        String raw = required(properties, key);
        final int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " must be an integer", ex);
        }
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(key + " must be between 1 and 65535");
        }
        return value;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing Engineering Client config value: " + key);
        }
        return value.trim();
    }
}
