package oss.relay.timing.timingpoint.infra.logging;

/** Effective runtime configuration owned by the reusable logging component. */
public final class LoggingConfig {
    public static final int DEFAULT_QUEUE_CAPACITY = 4096;
    public static final long DEFAULT_FLUSH_INTERVAL_MILLIS = 250L;

    private final LoggingLevel level;
    private final LoggingFileConfig file;
    private final int queueCapacity;
    private final long flushIntervalMillis;

    public LoggingConfig(LoggingLevel level, LoggingFileConfig file) {
        this(
                level,
                file,
                DEFAULT_QUEUE_CAPACITY,
                DEFAULT_FLUSH_INTERVAL_MILLIS);
    }

    public LoggingConfig(
            LoggingLevel level,
            LoggingFileConfig file,
            int queueCapacity,
            long flushIntervalMillis) {
        if (level == null) {
            throw new IllegalArgumentException("logging level must not be null");
        }
        if (file == null) {
            throw new IllegalArgumentException("logging file config must not be null");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "logging queueCapacity must be greater than zero");
        }
        if (flushIntervalMillis <= 0L) {
            throw new IllegalArgumentException(
                    "logging flushIntervalMillis must be greater than zero");
        }
        this.level = level;
        this.file = file;
        this.queueCapacity = queueCapacity;
        this.flushIntervalMillis = flushIntervalMillis;
    }

    public LoggingLevel level() {
        return level;
    }

    public LoggingFileConfig file() {
        return file;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    public long flushIntervalMillis() {
        return flushIntervalMillis;
    }
}
