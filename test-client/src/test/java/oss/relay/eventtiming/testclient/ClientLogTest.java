package oss.relay.eventtiming.testclient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientLogTest {
    @TempDir
    Path temp;

    @Test
    void retainsAndPublishesLocalClientRecords() throws Exception {
        AtomicReference<String> observed = new AtomicReference<>("");
        try (ClientLog log = ClientLog.open(temp, "INFO")) {
            log.subscribe(observed::set);
            log.debug("hidden");
            writeInfo(log);

            assertTrue(log.snapshot().contains("[INFO] - client started"));
            assertTrue(log.snapshot().contains(
                    "[testclient.ClientLogTest.writeInfo]"));
            assertEquals(log.snapshot(), observed.get());
        }

        try (var files = Files.list(temp)) {
            Path file = files.findFirst().orElseThrow();
            assertTrue(Files.readString(file).contains("client started"));
        }
    }

    @Test
    void changesLocalThresholdAtRuntime() throws Exception {
        try (ClientLog log = ClientLog.open(temp, "INFO")) {
            assertEquals("INFO", log.level());

            log.debug("hidden before change");
            assertTrue(!log.snapshot().contains("hidden before change"));

            log.setLevel("DEBUG");
            assertEquals("DEBUG", log.level());
            log.debug("visible after change");
            assertTrue(log.snapshot().contains("[DEBUG] - visible after change"));

            log.setLevel("ERROR");
            assertEquals("ERROR", log.level());
            log.info("hidden at error");
            log.error("visible error");
            assertTrue(!log.snapshot().contains("hidden at error"));
            assertTrue(log.snapshot().contains("[ERROR] - visible error"));
        }
    }

    private static void writeInfo(ClientLog log) {
        log.info("client started");
    }

}
