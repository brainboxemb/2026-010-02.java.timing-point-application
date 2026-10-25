package oss.relay.timing.timingpoint.infra.loggingserver;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

/** JUL sink that forwards records to the non-blocking engineering diagnostics queue. */
final class LiveLogHandler extends Handler {
    private final LoggingServer server;

    LiveLogHandler(LoggingServer server) {
        this.server = server;
        setLevel(java.util.logging.Level.ALL);
    }

    @Override
    public void publish(LogRecord record) {
        if (isLoggable(record)) {
            server.publish(record);
        }
    }

    @Override
    public void flush() {
        // Socket writer flushes each emitted record.
    }

    @Override
    public void close() {
        // Handler lifecycle is owned by LoggingServer.
    }
}
