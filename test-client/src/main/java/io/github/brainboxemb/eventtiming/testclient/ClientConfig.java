package io.github.brainboxemb.eventtiming.testclient;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final Path clientLogPath;
    private final String clientLogLevel;
    private final String registrationPrefix;

    private ClientConfig(
            String host,
            int apiHttpPort,
            int eventPort,
            int shellPort,
            int loggingServerPort,
            Path clientLogPath,
            String clientLogLevel,
            String registrationPrefix) {
        this.host = host;
        this.apiHttpPort = apiHttpPort;
        this.eventPort = eventPort;
        this.shellPort = shellPort;
        this.loggingServerPort = loggingServerPort;
        this.clientLogPath = clientLogPath;
        this.clientLogLevel = clientLogLevel;
        this.registrationPrefix = registrationPrefix;
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
                Path.of(required(properties, "client.log.path")),
                level,
                properties.getProperty("registration.prefix", "").trim());
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

    Path clientLogPath() {
        return clientLogPath;
    }

    String clientLogLevel() {
        return clientLogLevel;
    }

    String registrationPrefix() {
        return registrationPrefix;
    }

    URI apiEndpoint() {
        return URI.create("http://" + host + ":" + apiHttpPort);
    }

    URI eventEndpoint() {
        return URI.create("ws://" + host + ":" + eventPort + "/api/v1/events");
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
