package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Calculates a stage timing record from one active registration and its resolved race data.
 *
 * <p>Team assignment, stage-start lookup, active registration traversal and ranking belong
 * to the callers. This calculation owns no mutable state or reference-data indexes.</p>
 */
public final class StageTiming {

    private StageTiming() {
    }

    /**
     * Calculates the passage time for an already resolved team and start.
     *
     * <p>Missing team or start data is handled by the caller: this method only accepts
     * a complete input. Time-only starts use the latest occurrence not after the finish.</p>
     */
    public static StageTimingRecord resolve(
            TimingData registration,
            TeamId teamId,
            TimingTimestamp startTime,
            ZoneId raceTimeZone) {

        failIfInvalidArgument(registration != null, "registration must not be null");
        failIfInvalidArgument(teamId != null, "teamId must not be null");
        failIfInvalidArgument(startTime != null, "startTime must not be null");
        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");

        RegistrationId registrationId = ActiveRegistrations.registrationIdOrNull(registration);
        failIfInvalidArgument(registrationId != null, "TimingData must be a registration");
        TimingTimestamp finishTime = registration.effectiveTime();
        failIfIllegalState(finishTime.isAbsolute(), "committed registration time must be absolute");

        TimingTimestamp resolvedStart = resolveStart(startTime, finishTime, raceTimeZone);
        return new StageTimingRecord(
                registration.sequenceNumber(), registration.locationId(), teamId,
                registrationId, resolvedStart, finishTime);
    }

    /** Resolves a partial stage start to an absolute timestamp. */
    private static TimingTimestamp resolveStart(
            TimingTimestamp start, TimingTimestamp finish, ZoneId raceTimeZone) {

        if (start.isAbsolute()) {
            return start;
        }
        if (start.hasDate()) {
            return new TimingTimestamp(ZonedDateTime.of(start.date(), start.time(), raceTimeZone).toInstant());
        }

        LocalDate finishDate = finish.instant().atZone(raceTimeZone).toLocalDate();
        ZonedDateTime resolved = ZonedDateTime.of(finishDate, start.time(), raceTimeZone);
        if (resolved.toInstant().isAfter(finish.instant())) {
            resolved = ZonedDateTime.of(finishDate.minusDays(1L), start.time(), raceTimeZone);
        }
        return new TimingTimestamp(resolved.toInstant());
    }
}
