package oss.relay.eventtiming.timingpoint.infra.configuration;

/** Outcome of one attempted runtime configuration mutation. */
public enum ConfigurationUpdateResult {
    APPLIED,
    NO_CHANGE,
    INVALID,
    RESTART_REQUIRED
}
