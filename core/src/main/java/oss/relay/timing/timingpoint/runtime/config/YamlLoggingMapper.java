package oss.relay.timing.timingpoint.runtime.config;

import oss.relay.timing.timingpoint.infra.logging.LoggingConfig;
import oss.relay.timing.timingpoint.infra.logging.LoggingFileConfig;
import oss.relay.timing.timingpoint.infra.logging.LoggingLevel;
import oss.relay.timing.timingpoint.infra.loggingserver.LoggingServerConfig;

import java.util.Map;

/** Maps the runtime logging section of the application YAML schema. */
final class YamlLoggingMapper {
    private static final String LOGGING = "logging";
    private static final String LEVEL = "level";
    private static final String FILE = "file";
    private static final String PATH = "path";
    private static final String ROTATE_BYTES = "rotateBytes";
    private static final String RETAINED_FILES = "retainedFiles";
    private static final String LIVE = "live";
    private static final String BIND_ADDRESS = "bindAddress";
    private static final String PORT = "port";

    private YamlLoggingMapper() {
    }

    /** Maps the optional logging configuration used by the application runtime. */
    static LoggingConfig map(Object rawLogging) {
        if (rawLogging == null) {
            return null;
        }

        Map<?, ?> logging =
                YamlValues.requireMapping(
                        rawLogging,
                        LOGGING);
        YamlValues.rejectUnknownFields(
                logging,
                LOGGING,
                LEVEL,
                FILE,
                LIVE);
        if (!logging.containsKey(LEVEL)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + LOGGING
                            + "."
                            + LEVEL);
        }
        if (!logging.containsKey(FILE)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + LOGGING
                            + "."
                            + FILE);
        }

        String rawLevel =
                YamlValues.requireString(
                        logging.get(
                                LEVEL),
                        LOGGING
                                + "."
                                + LEVEL);
        LoggingLevel level;
        try {
            level =
                    LoggingLevel.valueOf(
                            rawLevel
                                    .trim()
                                    .toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    LOGGING
                            + "."
                            + LEVEL
                            + " must be TRACE, DEBUG, INFO, WARN or ERROR",
                    ex);
        }

        return new LoggingConfig(
                level,
                mapFile(
                        logging.get(
                                FILE)));
    }

    /** Maps the optional live logging server endpoint. */
    static LoggingServerConfig mapLive(Object rawLogging) {
        if (rawLogging == null) {
            return null;
        }

        Map<?, ?> logging =
                YamlValues.requireMapping(
                        rawLogging,
                        LOGGING);
        Object rawLive =
                logging.get(
                        LIVE);
        if (rawLive == null) {
            return null;
        }

        String field =
                LOGGING
                        + "."
                        + LIVE;
        Map<?, ?> values =
                YamlValues.endpointMapping(
                        rawLive,
                        field);
        return new LoggingServerConfig(
                YamlValues.requireString(
                        values.get(
                                BIND_ADDRESS),
                        field
                                + "."
                                + BIND_ADDRESS),
                YamlValues.requirePort(
                        values.get(
                                PORT),
                        field
                                + "."
                                + PORT));
    }

    /** Maps file logging rotation and retention settings. */
    private static LoggingFileConfig mapFile(Object rawFile) {
        String field =
                LOGGING
                        + "."
                        + FILE;
        Map<?, ?> values =
                YamlValues.requireMapping(
                        rawFile,
                        field);
        YamlValues.rejectUnknownFields(
                values,
                field,
                PATH,
                ROTATE_BYTES,
                RETAINED_FILES);
        return new LoggingFileConfig(
                YamlValues.requireString(
                        values.get(
                                PATH),
                        field
                                + "."
                                + PATH),
                YamlValues.requirePositiveInteger(
                        values.get(
                                ROTATE_BYTES),
                        field
                                + "."
                                + ROTATE_BYTES),
                YamlValues.requirePositiveInteger(
                        values.get(
                                RETAINED_FILES),
                        field
                                + "."
                                + RETAINED_FILES));
    }
}
