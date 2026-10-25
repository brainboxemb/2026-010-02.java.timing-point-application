package oss.relay.timing.testclient;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LiveLogClientTest {
    @Test
    void parsesLogAndLevelMessagesIndependentlyFromSi01Implementation() throws Exception {
        AtomicReference<LiveLogClient.LogEntry> entry = new AtomicReference<>();
        AtomicReference<String> level = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();

        LiveLogClient.Listener listener = new LiveLogClient.Listener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onLog(LiveLogClient.LogEntry value) {
                entry.set(value);
            }

            @Override
            public void onLevel(String value) {
                level.set(value);
            }

            @Override
            public void onDisconnected() {
            }

            @Override
            public void onError(String message) {
                error.set(message);
            }
        };

        LiveLogClient.dispatch(
                "{\"type\":\"log\",\"occurredAt\":\"2026-09-28T14:00:00Z\","
                        + "\"level\":\"INFO\",\"logger\":\"example.Logger\","
                        + "\"source\":\"example.Logger.run\",\"message\":\"hello\","
                        + "\"formatted\":\"16:00:00.000 - [INFO] - hello - [example.Logger.run]\\n\"}",
                listener);
        LiveLogClient.dispatch("{\"type\":\"level\",\"level\":\"DEBUG\"}", listener);

        assertEquals(Instant.parse("2026-09-28T14:00:00Z"), entry.get().occurredAt());
        assertEquals("INFO", entry.get().level());
        assertEquals("example.Logger", entry.get().logger());
        assertEquals("example.Logger.run", entry.get().source());
        assertEquals("hello", entry.get().message());
        assertEquals(
                "16:00:00.000 - [INFO] - hello - [example.Logger.run]\n",
                entry.get().formatted());
        assertNull(entry.get().thrown());
        assertEquals("DEBUG", level.get());
        assertNull(error.get());
    }
}
