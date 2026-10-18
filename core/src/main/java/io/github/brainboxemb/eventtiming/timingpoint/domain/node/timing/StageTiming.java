package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.TeamAssignments;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Resolves active registrations to stage passages.
 *
 * <p>Every active registration remains an independent passage. Multiple registrations for
 * the same team therefore remain multiple StageTime rows. Team ranking is maintained by
 * {@link TeamRanking}, which selects one active registration per team separately.</p>
 */
public final class StageTiming {
    private final EventData eventData;
    private final TeamAssignments teamAssignments;
    private final StageStartTimes stageStartTimes;

    /** Creates the passage resolver over current reference and race data. */
    public StageTiming(EventData eventData, TeamAssignments teamAssignments, StageStartTimes stageStartTimes) {

        failIfInvalidArgument(eventData != null, "eventData must not be null");
        failIfInvalidArgument(teamAssignments != null, "teamAssignments must not be null");
        failIfInvalidArgument(stageStartTimes != null, "stageStartTimes must not be null");

        this.eventData = eventData;
        this.teamAssignments = teamAssignments;
        this.stageStartTimes = stageStartTimes;
    }

    /**
     * Visits at most {@code limit} resolved active passages in active-record order.
     *
     * <p>Unresolved registrations are skipped. No team deduplication or ranking occurs.</p>
     *
     * @return number of visited passages
     */
    public int visit(
            ActiveRegistrations activeRegistrations,
            ZoneId raceTimeZone,
            int limit,
            Consumer<StageTime> visitor) {

        failIfInvalidArgument(activeRegistrations != null, "activeRegistrations must not be null");
        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        int visited = 0;
        for (TimingData registration : activeRegistrations.values()) {
            if (visited >= limit) {
                break;
            }

            StageTime stageTime = resolve(registration, raceTimeZone);
            if (stageTime != null) {
                visitor.accept(stageTime);
                visited++;
            }
        }
        return visited;
    }

    /** Resolves one active registration using its current TeamId. */
    StageTime resolve(TimingData registration, ZoneId raceTimeZone) {

        RegistrationId registrationId = registrationId(registration);
        TeamId teamId = resolveTeamId(registrationId);
        return resolve(registration, teamId, raceTimeZone);
    }

    /** Resolves one active registration using an already selected TeamId. */
    StageTime resolve(TimingData registration, TeamId teamId, ZoneId raceTimeZone) {

        failIfInvalidArgument(registration != null, "registration must not be null");
        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");

        StageTime stageTime = null;
        TimingTimestamp start = teamId == null ? null : stageStartTimes.startFor(teamId);
        if (start != null) {
            RegistrationId registrationId = registrationId(registration);
            TimingTimestamp registrationTime = registration.effectiveTime();
            failIfIllegalState(registrationTime.isAbsolute(), "committed TimingData effectiveTime must be absolute");

            TimingTimestamp resolvedStart = resolveStart(start, registrationTime, raceTimeZone);
            stageTime =
                    new StageTime(
                            registration.sequenceNumber(),
                            registration.locationId(),
                            teamId,
                            registrationId,
                            resolvedStart,
                            registrationTime);
        }
        return stageTime;
    }

    /** Resolves TeamId from configured EventData first and live assignment state second. */
    private TeamId resolveTeamId(RegistrationId registrationId) {

        TeamId configuredTeamId = eventData.teamIdFor(registrationId);
        return configuredTeamId != null ? configuredTeamId : teamAssignments.teamIdFor(registrationId);
    }

    /** Extracts RegistrationId from an active automatic or manual registration. */
    private static RegistrationId registrationId(TimingData data) {

        RegistrationId registrationId = null;
        if (data instanceof TimingData.AutomaticRegistration) {
            registrationId = ((TimingData.AutomaticRegistration) data).registrationId();
        } else if (data instanceof TimingData.ManualRegistration) {
            registrationId = ((TimingData.ManualRegistration) data).registrationId();
        }
        failIfIllegalState(registrationId != null, "TimingData is not a registration");
        return registrationId;
    }

    /** Resolves partial stage-start information to an absolute timestamp. */
    private static TimingTimestamp resolveStart(
            TimingTimestamp start,
            TimingTimestamp registrationTime,
            ZoneId raceTimeZone) {

        if (start.isAbsolute()) {
            return start;
        }
        if (start.hasDate()) {
            return absolute(ZonedDateTime.of(start.date(), start.time(), raceTimeZone));
        }

        LocalDate registrationDate = registrationTime.instant().atZone(raceTimeZone).toLocalDate();
        ZonedDateTime resolved = ZonedDateTime.of(registrationDate, start.time(), raceTimeZone);
        if (resolved.toInstant().isAfter(registrationTime.instant())) {
            resolved = ZonedDateTime.of(registrationDate.minusDays(1L), start.time(), raceTimeZone);
        }
        return absolute(resolved);
    }

    /** Converts one zoned civil time into an absolute TimingTimestamp. */
    private static TimingTimestamp absolute(ZonedDateTime value) {

        return new TimingTimestamp(value.toInstant());
    }
}
