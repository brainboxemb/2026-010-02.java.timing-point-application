package io.github.brainboxemb.eventtiming.timingpoint.infra.logging;

/**
 * Runtime capability for reading and changing the global application log level.
 *
 * <p>Presentation uses this small boundary instead of depending on the concrete
 * Logging component or logging backend.</p>
 */
public interface LoggingLevelControl {
    LoggingLevel level();

    void setLevel(LoggingLevel level);
}
