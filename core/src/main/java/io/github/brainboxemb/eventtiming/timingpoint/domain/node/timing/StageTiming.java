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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Derives the current elapsed stage times and ranking for one TimingNode.
 *
 * <p>StageTiming is read-side only. It interprets committed registration
 * history together with current EventData, TeamAssignments and StageStartTimes;
 * it never rewrites TimingData or race state.</p>
 */
public final class StageTiming {
    private final EventData eventData;
    private final TeamAssignments teamAssignments;
    private final StageStartTimes stageStartTimes;

    public StageTiming(EventData eventData, TeamAssignments teamAssignments, StageStartTimes stageStartTimes) {
        checkArgument(eventData != null, "eventData must not be null");
        checkArgument(teamAssignments != null, "teamAssignments must not be null");
        checkArgument(stageStartTimes != null, "stageStartTimes must not be null");
        this.eventData = eventData;
        this.teamAssignments = teamAssignments;
        this.stageStartTimes = stageStartTimes;
    }

    /**
     * Returns the current resolved ranking, fastest elapsed time first.
     *
     * <p>Lifecycle records and revoked registrations are not ranking rows.
     * Registrations whose TeamId or start time cannot currently be resolved are
     * simply absent; StageTiming never invents an elapsed time for incomplete
     * input.</p>
     */
    public List<StageTime> ranking(List<TimingData> committedTimingData, ZoneId raceTimeZone) {
        checkArgument(committedTimingData != null, "committedTimingData must not be null");
        checkArgument(raceTimeZone != null, "raceTimeZone must not be null");

        List<TimingData> activeRegistrations = activeRegistrations(committedTimingData);
        List<StageTime> resolved = new ArrayList<StageTime>();

        for (TimingData registration : activeRegistrations) {
            RegistrationId registrationId = registrationId(registration);
            TeamId teamId = resolveTeamId(registrationId);
            if (teamId == null) {
                continue;
            }

            TimingTimestamp start = stageStartTimes.startFor(teamId);
            if (start == null) {
                continue;
            }

            TimingTimestamp registrationTime = registration.effectiveTime();
            if (!registrationTime.isAbsolute()) {
                throw new IllegalStateException("committed TimingData effectiveTime must be absolute");
            }

            TimingTimestamp resolvedStart = resolveStart(start, registrationTime, raceTimeZone);
            resolved.add(new StageTime(
                    0,
                    teamId,
                    registrationId,
                    Duration.between(resolvedStart.instant(), registrationTime.instant())));
        }

        Collections.sort(
                resolved,
                Comparator.comparing(StageTime::elapsed)
                        .thenComparing(value -> value.teamId().value())
                        .thenComparing(value -> value.registrationId().value()));

        List<StageTime> ranked = new ArrayList<StageTime>(resolved.size());
        Duration previousElapsed = null;
        int rank = 0;
        for (int index = 0; index < resolved.size(); index++) {
            StageTime value = resolved.get(index);
            if (previousElapsed == null || !previousElapsed.equals(value.elapsed())) {
                rank = index + 1;
                previousElapsed = value.elapsed();
            }
            ranked.add(new StageTime(rank, value.teamId(), value.registrationId(), value.elapsed()));
        }

        return Collections.unmodifiableList(ranked);
    }

    /**
     * Rebuilds the active registration view from append-only ADD/REV records.
     */
    private static List<TimingData> activeRegistrations(List<TimingData> committedTimingData) {
        List<TimingData> active = new ArrayList<TimingData>();

        for (TimingData data : committedTimingData) {
            TimingData.RegistrationAction action = registrationAction(data);
            if (action == null) {
                continue;
            }

            if (action == TimingData.RegistrationAction.ADD) {
                active.add(data);
            } else {
                removeMatchingRegistration(active, data);
            }
        }
        return active;
    }

    private static void removeMatchingRegistration(List<TimingData> active, TimingData revoke) {
        RegistrationId revokeRegistrationId = registrationId(revoke);

        /*
         * A REV carries the original registration identity: RegistrationId,
         * LocationId and effective time. Scan backwards so multiplicity remains
         * correct if equivalent ADD records exist.
         */
        for (int index = active.size() - 1;
                index >= 0;
                index--) {
            TimingData activeRegistration = active.get(index);
            if (revokeRegistrationId.equals(
                        registrationId(activeRegistration))
                    && Objects.equals(
                            revoke.locationId(),
                            activeRegistration.locationId())
                    && revoke.effectiveTime().equals(
                            activeRegistration.effectiveTime())) {
                active.remove(index);
                return;
            }
        }
    }

    private TeamId resolveTeamId(RegistrationId registrationId) {
        TeamId configuredTeamId = eventData.teamIdFor(registrationId);
        return configuredTeamId != null ? configuredTeamId : teamAssignments.teamIdFor(registrationId);
    }

    private static RegistrationId registrationId(TimingData data) {
        if (data instanceof TimingData.AutomaticRegistration) {
            return ((TimingData.AutomaticRegistration) data).registrationId();
        }
        if (data instanceof TimingData.ManualRegistration) {
            return ((TimingData.ManualRegistration) data).registrationId();
        }
        throw new IllegalArgumentException("TimingData is not a registration");
    }

    private static TimingData.RegistrationAction registrationAction(TimingData data) {
        if (data instanceof TimingData.AutomaticRegistration) {
            return ((TimingData.AutomaticRegistration) data).action();
        }
        if (data instanceof TimingData.ManualRegistration) {
            return ((TimingData.ManualRegistration) data).action();
        }
        return null;
    }

    private static TimingTimestamp resolveStart(
            TimingTimestamp start, TimingTimestamp registrationTime, ZoneId raceTimeZone) {
        if (start.isAbsolute()) {
            return start;
        }

        if (start.hasDate()) {
            return absolute(ZonedDateTime.of(start.date(), start.time(), raceTimeZone));
        }

        /*
         * A time-only start has no race day. Resolve it to the most recent civil
         * occurrence that is not after the registration, including midnight
         * rollover.
         */
        LocalDate registrationDate = registrationTime.instant().atZone(raceTimeZone).toLocalDate();
        ZonedDateTime resolved = ZonedDateTime.of(registrationDate, start.time(), raceTimeZone);

        if (resolved.toInstant().isAfter(registrationTime.instant())) {
            resolved = ZonedDateTime.of(registrationDate.minusDays(1L), start.time(), raceTimeZone);
        }

        return absolute(resolved);
    }

    private static TimingTimestamp absolute(ZonedDateTime value) {
        return new TimingTimestamp(value.toInstant());
    }
}
