package oss.relay.eventtiming.timingpoint.infra.loggingserver;

import oss.relay.eventtiming.timingpoint.infra.logging.Logging;
import oss.relay.eventtiming.timingpoint.infra.logging.LoggingConfig;
import oss.relay.eventtiming.timingpoint.infra.logging.LoggingFileConfig;
import oss.relay.eventtiming.timingpoint.infra.logging.LoggingLevel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Logger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LoggingServerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void streamsLiveRecordsAndChangesRuntimeLevel() throws Exception {
        File logDirectory = temporaryFolder.newFolder("logs");
        LoggingConfig config = new LoggingConfig(
                LoggingLevel.INFO,
                new LoggingFileConfig(logDirectory.getAbsolutePath(), 4096, 1));

        try (Logging logging = Logging.start(config);
                LoggingServer server =
                        new LoggingServer(new LoggingServerConfig("127.0.0.1", 0), logging)) {
            server.start();

            try (Socket socket = new Socket("127.0.0.1", server.boundPort());
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(
                                    socket.getInputStream(), StandardCharsets.UTF_8));
                    BufferedWriter writer = new BufferedWriter(
                            new OutputStreamWriter(
                                    socket.getOutputStream(), StandardCharsets.UTF_8))) {

                assertTrue(readUntil(reader, "\"type\":\"level\"").contains("\"INFO\""));

                Logger logger = Logger.getLogger("test.a08.live");
                logger.info("A08 live message");
                assertTrue(readUntil(reader, "A08 live message").contains("\"type\":\"log\""));

                writer.write("SET_LEVEL DEBUG");
                writer.newLine();
                writer.flush();
                assertTrue(readUntil(reader, "\"type\":\"level\"").contains("\"DEBUG\""));
                assertEquals(LoggingLevel.DEBUG, logging.level());

                logger.fine("A08 debug message");
                assertTrue(readUntil(reader, "A08 debug message").contains("A08 debug message"));
            }
        }

        File[] retained = logDirectory.listFiles(
                (dir, name) -> name.matches("\\d{8}-\\d{6}(?:-\\d{2,})?\\.txt"));
        assertTrue(retained != null && retained.length == 1);
        String fileText =
                new String(Files.readAllBytes(retained[0].toPath()), StandardCharsets.UTF_8);
        assertTrue(fileText.contains(" - [INFO] - A08 live message - ["));
        assertTrue(fileText.contains(" - [DEBUG] - A08 debug message - ["));
    }

    private static String readUntil(BufferedReader reader, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            if (line.contains(expected)) {
                return line;
            }
        }
        throw new AssertionError("Did not receive line containing: " + expected);
    }
}
