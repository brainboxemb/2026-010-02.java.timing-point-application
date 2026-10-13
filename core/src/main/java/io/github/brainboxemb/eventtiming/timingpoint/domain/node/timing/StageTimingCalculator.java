package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TeamAssignments;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.data.StageTiming;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Calculates read-side stage timing from committed TimingData and current
 * race/reference data.
 *
 * <p>The calculator is stateless. It does not modify TimingData, assignments or
 * stage starts. A later race-data change therefore affects a later calculation
 * without rewriting the committed LogBook.</p>
 */
public final class StageTimingCalculator {
    private final EventData eventData;
    private final TeamAssignments teamAssignments;
    private final StageStartTimes stageStartTimes;

    public StageTimingCalculator(
            EventData eventData,
            TeamAssignments teamAssignments,
            StageStartTimes stageStartTimes) {
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        if (teamAssignments == null) {
            throw new IllegalArgumentException(
                    "teamAssignments must not be null");
        }
        if (stageStartTimes == null) {
            throw new IllegalArgumentException(
                    "stageStartTimes must not be null");
        }
        this.eventData = eventData;
        this.teamAssignments = teamAssignments;
        this.stageStartTimes = stageStartTimes;
    }

    public StageTiming calculate(
            TimingData data,
            ZoneId raceTimeZone) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null");
        }
        if (raceTimeZone == null) {
            throw new IllegalArgumentException(
                    "raceTimeZone must not be null");
        }

        if (data instanceof TimingData.AutomaticRegistration) {
            TimingData.AutomaticRegistration registration =
                    (TimingData.AutomaticRegistration) data;
            return calculateRegistration(
                    data,
                    registration.registrationId(),
                    registration.action(),
                    raceTimeZone);
        }

        if (data instanceof TimingData.ManualRegistration) {
            TimingData.ManualRegistration registration =
                    (TimingData.ManualRegistration) data;
            return calculateRegistration(
                    data,
                    registration.registrationId(),
                    registration.action(),
                    raceTimeZone);
        }

        return StageTiming.notRegistration(
                data.sequenceNumber());
    }

    private StageTiming calculateRegistration(
            TimingData data,
            RegistrationId registrationId,
            TimingData.RegistrationAction action,
            ZoneId raceTimeZone) {
        if (action == TimingData.RegistrationAction.REV) {
            return StageTiming.revoked(
                    data.sequenceNumber(),
                    registrationId);
        }

        TeamId teamId =
                resolveTeamId(
                        registrationId);
        if (teamId == null) {
            return StageTiming.teamUnresolved(
                    data.sequenceNumber(),
                    registrationId);
        }

        TimingTimestamp start =
                stageStartTimes.startFor(
                        teamId);
        if (start == null) {
            return StageTiming.startUnresolved(
                    data.sequenceNumber(),
                    registrationId,
                    teamId);
        }

        TimingTimestamp registrationTime =
                data.effectiveTime();
        if (!registrationTime.isAbsolute()) {
            throw new IllegalArgumentException(
                    "TimingData effectiveTime must be absolute");
        }

        TimingTimestamp resolvedStart =
                resolveStart(
                        start,
                        registrationTime,
                        raceTimeZone);

        return StageTiming.resolved(
                data.sequenceNumber(),
                registrationId,
                teamId,
                resolvedStart,
                Duration.between(
                        resolvedStart.instant(),
                        registrationTime.instant()));
    }

    private TeamId resolveTeamId(
            RegistrationId registrationId) {
        TeamId fixed =
                eventData.teamIdFor(
                        registrationId);
        return fixed != null
                ? fixed
                : teamAssignments.teamIdFor(
                        registrationId);
    }

    private static TimingTimestamp resolveStart(
            TimingTimestamp start,
            TimingTimestamp registrationTime,
            ZoneId raceTimeZone) {
        if (start.isAbsolute()) {
            return start;
        }

        /*
         * An explicit date is authoritative. The race timezone supplies only
         * the missing zone context; TimingTimestamp itself does not invent it.
         */
        if (start.hasDate()) {
            return absolute(
                    ZonedDateTime.of(
                            start.date(),
                            start.time(),
                            raceTimeZone));
        }

        /*
         * A time-only start has no race day. Resolve it to the most recent civil
         * occurrence that is not after the accepted registration. This is the
         * stage-specific midnight rollover rule and deliberately lives here.
         */
        LocalDate registrationDate =
                registrationTime.instant()
                        .atZone(raceTimeZone)
                        .toLocalDate();
        ZonedDateTime resolved =
                ZonedDateTime.of(
                        registrationDate,
                        start.time(),
                        raceTimeZone);

        if (resolved.toInstant()
                .isAfter(registrationTime.instant())) {
            resolved =
                    ZonedDateTime.of(
                            registrationDate.minusDays(1L),
                            start.time(),
                            raceTimeZone);
        }

        return absolute(resolved);
    }

    private static TimingTimestamp absolute(
            ZonedDateTime value) {
        return new TimingTimestamp(
                value.toInstant());
    }
}
