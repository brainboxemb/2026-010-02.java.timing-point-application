package oss.relay.timing.timingpoint.infra.logging;

import java.util.logging.Logger;

/** Owns the configured global logging level plus a temporary runtime override. */
final class LoggingControl {
    private final Logger rootLogger;
    private LoggingLevel level;

    LoggingControl(Logger rootLogger, LoggingLevel initialLevel) {
        this.rootLogger = rootLogger;
        setLevel(initialLevel);
    }

    synchronized LoggingLevel level() {
        return level;
    }

    synchronized void setLevel(LoggingLevel newLevel) {
        if (newLevel == null) {
            throw new IllegalArgumentException("logging level must not be null");
        }
        rootLogger.setLevel(toJulLevel(newLevel));
        level = newLevel;
    }

    static java.util.logging.Level toJulLevel(LoggingLevel level) {
        switch (level) {
            case TRACE:
                return java.util.logging.Level.FINEST;
            case DEBUG:
                return java.util.logging.Level.FINE;
            case INFO:
                return java.util.logging.Level.INFO;
            case WARN:
                return java.util.logging.Level.WARNING;
            case ERROR:
                return java.util.logging.Level.SEVERE;
            default:
                throw new IllegalArgumentException("Unsupported logging level: " + level);
        }
    }

    static String semanticLevel(java.util.logging.Level level) {
        int value = level.intValue();
        if (value >= java.util.logging.Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (value >= java.util.logging.Level.WARNING.intValue()) {
            return "WARN";
        }
        if (value >= java.util.logging.Level.INFO.intValue()) {
            return "INFO";
        }
        if (value >= java.util.logging.Level.FINE.intValue()) {
            return "DEBUG";
        }
        return "TRACE";
    }
}
