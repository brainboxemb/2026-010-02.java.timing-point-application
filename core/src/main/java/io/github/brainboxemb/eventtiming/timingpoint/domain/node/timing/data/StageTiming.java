package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.data;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.Duration;

/**
 * Immutable result of one stage-timing calculation.
 *
 * <p>This class contains result data only. Interpretation of TimingData,
 * TeamAssignments, StageStartTimes and race-timezone rules belongs to
 * StageTimingCalculator.</p>
 */
public final class StageTiming {
    private final StageTimingStatus status;
    private final long sequenceNumber;
    private final RegistrationId registrationId;
    private final TeamId teamId;
    private final TimingTimestamp start;
    private final Duration elapsed;

    private StageTiming(
            StageTimingStatus status,
            long sequenceNumber,
            RegistrationId registrationId,
            TeamId teamId,
            TimingTimestamp start,
            Duration elapsed) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "status must not be null");
        }
        this.status = status;
        this.sequenceNumber = sequenceNumber;
        this.registrationId = registrationId;
        this.teamId = teamId;
        this.start = start;
        this.elapsed = elapsed;
    }

    public static StageTiming timingDataNotFound(
            long sequenceNumber) {
        return new StageTiming(
                StageTimingStatus.TIMING_DATA_NOT_FOUND,
                sequenceNumber,
                null,
                null,
                null,
                null);
    }

    public static StageTiming notRegistration(
            long sequenceNumber) {
        return new StageTiming(
                StageTimingStatus.NOT_REGISTRATION,
                sequenceNumber,
                null,
                null,
                null,
                null);
    }

    public static StageTiming revoked(
            long sequenceNumber,
            RegistrationId registrationId) {
        return new StageTiming(
                StageTimingStatus.REVOKED,
                sequenceNumber,
                registrationId,
                null,
                null,
                null);
    }

    public static StageTiming teamUnresolved(
            long sequenceNumber,
            RegistrationId registrationId) {
        return new StageTiming(
                StageTimingStatus.TEAM_UNRESOLVED,
                sequenceNumber,
                registrationId,
                null,
                null,
                null);
    }

    public static StageTiming startUnresolved(
            long sequenceNumber,
            RegistrationId registrationId,
            TeamId teamId) {
        return new StageTiming(
                StageTimingStatus.START_UNRESOLVED,
                sequenceNumber,
                registrationId,
                teamId,
                null,
                null);
    }

    public static StageTiming resolved(
            long sequenceNumber,
            RegistrationId registrationId,
            TeamId teamId,
            TimingTimestamp start,
            Duration elapsed) {
        if (start == null || !start.isAbsolute()) {
            throw new IllegalArgumentException(
                    "resolved start must be an absolute TimingTimestamp");
        }
        if (elapsed == null) {
            throw new IllegalArgumentException(
                    "elapsed must not be null");
        }
        return new StageTiming(
                StageTimingStatus.RESOLVED,
                sequenceNumber,
                registrationId,
                teamId,
                start,
                elapsed);
    }

    public StageTimingStatus status() {
        return status;
    }

    public boolean resolved() {
        return status == StageTimingStatus.RESOLVED;
    }

    public long sequenceNumber() {
        return sequenceNumber;
    }

    public RegistrationId registrationId() {
        return registrationId;
    }

    public TeamId teamId() {
        return teamId;
    }

    /**
     * Absolute stage start used for this calculation, when resolved.
     */
    public TimingTimestamp start() {
        return start;
    }

    public Duration elapsed() {
        return elapsed;
    }
}
