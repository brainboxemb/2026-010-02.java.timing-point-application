package io.github.brainboxemb.eventtiming.testclient;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientConfigTest {
    @TempDir
    Path temp;

    @Test
    void loadsOneConfigForAllEngineeringBoundaries() throws Exception {
        Path file = temp.resolve("engineering-client.properties");
        Files.writeString(file, String.join(System.lineSeparator(),
                "target.host=127.0.0.1",
                "api.http.port=8081",
                "api.events.port=8082",
                "remoteShell.port=8023",
                "loggingServer.port=8030",
                "client.log.path=logs/engineering-client",
                "client.log.level=DEBUG",
                "registration.prefix=N"));

        ClientConfig config = ClientConfig.load(file);

        assertEquals("127.0.0.1", config.host());
        assertEquals(8081, config.apiHttpPort());
        assertEquals(8082, config.eventPort());
        assertEquals(8023, config.shellPort());
        assertEquals(8030, config.loggingServerPort());
        assertEquals(Path.of("logs/engineering-client"), config.clientLogPath());
        assertEquals("DEBUG", config.clientLogLevel());
        assertEquals("N", config.registrationPrefix());
        assertEquals("http://127.0.0.1:8081", config.apiEndpoint().toString());
        assertEquals(
                "ws://127.0.0.1:8082/api/v1/events",
                config.eventEndpoint().toString());
    }

    @Test
    void resolvesDefaultConfigFromRepositoryWorkingDirectory() throws Exception {
        Path repository = temp.resolve("repository");
        Path config = repository.resolve("config").resolve("engineering-client.properties");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "# marker");

        assertEquals(config, ClientConfig.defaultPath(repository));
    }

    @Test
    void resolvesDefaultConfigFromTestClientWorkingDirectory() throws Exception {
        Path repository = temp.resolve("repository");
        Path client = repository.resolve("test-client");
        Path config = repository.resolve("config").resolve("engineering-client.properties");
        Files.createDirectories(client);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "# marker");

        assertEquals(config, ClientConfig.defaultPath(client));
    }

    @Test
    void rejectsInvalidBoundaryPorts() throws Exception {
        Path file = temp.resolve("engineering-client.properties");
        Files.writeString(file, String.join(System.lineSeparator(),
                "target.host=127.0.0.1",
                "api.http.port=0",
                "api.events.port=8082",
                "remoteShell.port=8023",
                "loggingServer.port=8030",
                "client.log.path=logs/engineering-client",
                "client.log.level=INFO"));

        assertThrows(IllegalArgumentException.class, () -> ClientConfig.load(file));
    }
}
