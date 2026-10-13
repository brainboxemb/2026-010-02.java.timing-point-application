package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.data;

/**
 * Outcome of one stage-timing calculation.
 */
public enum StageTimingStatus {
    RESOLVED,
    TIMING_DATA_NOT_FOUND,
    NOT_REGISTRATION,
    REVOKED,
    TEAM_UNRESOLVED,
    START_UNRESOLVED
}
