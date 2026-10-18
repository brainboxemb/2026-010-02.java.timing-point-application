package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.TeamAssignments;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Derives current elapsed stage times and ranking for one TimingNode.
 *
 * <p>StageTiming is read-side only. It interprets active registrations together with
 * current EventData, TeamAssignments and StageStartTimes. It retains only the requested
 * number of fastest rows while calculating a ranking.</p>
 */
public final class StageTiming {
    private static final Comparator<StageTime> FASTEST_FIRST =
            Comparator.comparing(StageTime::elapsed)
                    .thenComparing(value -> value.teamId().value())
                    .thenComparing(value -> value.registrationId().value());

    private final EventData eventData;
    private final TeamAssignments teamAssignments;
    private final StageStartTimes stageStartTimes;

    /** Creates the read-side timing calculator over current reference and race data. */
    public StageTiming(EventData eventData, TeamAssignments teamAssignments, StageStartTimes stageStartTimes) {

        failIfInvalidArgument(eventData != null, "eventData must not be null");
        failIfInvalidArgument(teamAssignments != null, "teamAssignments must not be null");
        failIfInvalidArgument(stageStartTimes != null, "stageStartTimes must not be null");

        this.eventData = eventData;
        this.teamAssignments = teamAssignments;
        this.stageStartTimes = stageStartTimes;
    }

    /**
     * Visits at most {@code limit} fastest resolved stage times.
     *
     * <p>The visitor runs synchronously on the caller's thread. Unresolved registrations
     * are skipped. Temporary ranking storage is bounded by {@code limit}.</p>
     *
     * @return number of visited rows
     */
    public int visitRanking(
            ActiveRegistrations activeRegistrations,
            ZoneId raceTimeZone,
            int limit,
            Consumer<StageTime> visitor) {

        failIfInvalidArgument(activeRegistrations != null, "activeRegistrations must not be null");
        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        if (limit == 0) {
            return 0;
        }

        PriorityQueue<StageTime> fastest = new PriorityQueue<StageTime>(limit, FASTEST_FIRST.reversed());
        for (TimingData registration : activeRegistrations.values()) {
            StageTime stageTime = resolveStageTime(registration, raceTimeZone);
            if (stageTime != null) {
                fastest.offer(stageTime);
                if (fastest.size() > limit) {
                    fastest.poll();
                }
            }
        }

        List<StageTime> ordered = new ArrayList<StageTime>(fastest);
        ordered.sort(FASTEST_FIRST);
        return visitRanked(ordered, visitor);
    }

    /** Resolves one active registration to an unranked elapsed stage time. */
    private StageTime resolveStageTime(TimingData registration, ZoneId raceTimeZone) {

        RegistrationId registrationId = registrationId(registration);
        TeamId teamId = resolveTeamId(registrationId);
        TimingTimestamp start = teamId == null ? null : stageStartTimes.startFor(teamId);
        if (start == null) {
            return null;
        }

        TimingTimestamp registrationTime = registration.effectiveTime();
        failIfIllegalState(registrationTime.isAbsolute(), "committed TimingData effectiveTime must be absolute");

        TimingTimestamp resolvedStart = resolveStart(start, registrationTime, raceTimeZone);
        return new StageTime(0, teamId, registrationId, resolvedStart, registrationTime);
    }

    /** Visits the ordered rows with competition ranking applied. */
    private static int visitRanked(List<StageTime> ordered, Consumer<StageTime> visitor) {

        Duration previousElapsed = null;
        int rank = 0;
        for (int index = 0; index < ordered.size(); index++) {
            StageTime value = ordered.get(index);
            if (previousElapsed == null || !previousElapsed.equals(value.elapsed())) {
                rank = index + 1;
                previousElapsed = value.elapsed();
            }
            visitor.accept(
                    new StageTime(
                            rank,
                            value.teamId(),
                            value.registrationId(),
                            value.startedAt(),
                            value.finishedAt()));
        }
        return ordered.size();
    }

    /** Resolves TeamId from configured EventData first and live assignment state second. */
    private TeamId resolveTeamId(RegistrationId registrationId) {

        TeamId configuredTeamId = eventData.teamIdFor(registrationId);
        return configuredTeamId != null ? configuredTeamId : teamAssignments.teamIdFor(registrationId);
    }

    /** Extracts RegistrationId from an active automatic or manual registration. */
    private static RegistrationId registrationId(TimingData data) {

        if (data instanceof TimingData.AutomaticRegistration) {
            return ((TimingData.AutomaticRegistration) data).registrationId();
        }
        if (data instanceof TimingData.ManualRegistration) {
            return ((TimingData.ManualRegistration) data).registrationId();
        }
        throw new IllegalArgumentException("TimingData is not a registration");
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
