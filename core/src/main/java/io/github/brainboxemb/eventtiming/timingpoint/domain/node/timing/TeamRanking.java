package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeSet;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Maintains the active registration activeRegistrations used for team ranking.
 *
 * <p>ActiveRegistration selection is maintained incrementally. Each location/team keeps its active
 * registrations ordered by finish time, so the selected registration is always the first
 * activeRegistration. Ranking queries therefore inspect one selected registration per team instead
 * of scanning all active registrations.</p>
 */
public final class TeamRanking {
    private static final Comparator<ActiveRegistration> REGISTRATION_ORDER =
            Comparator.comparing((ActiveRegistration value) -> value.registration.effectiveTime().instant())
                    .thenComparingLong(value -> value.registration.sequenceNumber());

    private static final Comparator<StageTimingRecord> FASTEST_TEAM_FIRST =
            Comparator.comparing(StageTimingRecord::elapsed)
                    .thenComparing(value -> value.teamId().value());

    private final RaceDataState raceData;
    private final Map<Long, ActiveRegistration> activeRegistrationsBySequence = new HashMap<>();
    private final Map<RegistrationId, Map<Long, ActiveRegistration>> activeRegistrationsByRegistrationId = new HashMap<>();
    private final Map<LocationId, Map<TeamId, NavigableSet<ActiveRegistration>>> activeRegistrationsByLocationAndTeam =
            new HashMap<>();

    /** Creates the ranking index over the shared StageTiming resolver. */
    public TeamRanking(RaceDataState raceData) {

        failIfInvalidArgument(raceData != null, "raceData must not be null");
        this.raceData = raceData;
    }

    /** Clears all active ranking activeRegistrations before LogBook reconstruction. */
    public void reset() {

        activeRegistrationsBySequence.clear();
        activeRegistrationsByRegistrationId.clear();
        activeRegistrationsByLocationAndTeam.clear();
    }

    /** Applies one actual active-registration ADD or matched REV. */
    public void apply(ActiveRegistrations.Change change) {

        if (change != null) {
            if (change.action() == ActiveRegistrations.Change.Action.ADDED) {
                addActiveRegistration(change.registration(), change.teamId());
            } else {
                removeActiveRegistration(change.registration().sequenceNumber());
            }
        }
    }

    /** Moves only the active activeRegistrations for one RegistrationId to its current team. */
    public void teamAssignmentChanged(RegistrationId registrationId, TeamId teamId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");

        Map<Long, ActiveRegistration> registrations = activeRegistrationsByRegistrationId.get(registrationId);
        if (registrations != null) {
            for (ActiveRegistration activeRegistration : registrations.values()) {
                if (!Objects.equals(activeRegistration.teamId, teamId)) {
                    removeFromTeam(activeRegistration);
                    activeRegistration.teamId = teamId;
                    addToTeam(activeRegistration);
                }
            }
        }
    }

    /**
     * Visits at most {@code limit} fastest teams at one location.
     *
     * <p>Only the currently selected registration for each team is resolved to StageTimingRecord.
     * Temporary ranking storage is bounded by {@code limit}.</p>
     *
     * @return number of visited teams
     */
    public int visit(
            LocationId locationId,
            ZoneId raceTimeZone,
            int limit,
            Consumer<TeamRank> visitor) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        if (limit == 0) {
            return 0;
        }

        Map<TeamId, NavigableSet<ActiveRegistration>> teams = activeRegistrationsByLocationAndTeam.get(locationId);
        if (teams == null) {
            return 0;
        }

        PriorityQueue<StageTimingRecord> fastest =
                new PriorityQueue<StageTimingRecord>(limit, FASTEST_TEAM_FIRST.reversed());

        for (Map.Entry<TeamId, NavigableSet<ActiveRegistration>> entry : teams.entrySet()) {
            ActiveRegistration activeRegistration = entry.getValue().first();
            TimingTimestamp start = raceData.stageStartTimeFor(entry.getKey());
            if (start != null) {
                StageTimingRecord timingRecord =
                        StageTiming.resolve(activeRegistration.registration, entry.getKey(), start, raceTimeZone);
                fastest.offer(timingRecord);
                if (fastest.size() > limit) {
                    fastest.poll();
                }
            }
        }

        List<StageTimingRecord> ordered = new ArrayList<StageTimingRecord>(fastest);
        ordered.sort(FASTEST_TEAM_FIRST);
        return visitRanked(ordered, visitor);
    }

    /** Adds one active registration activeRegistration to all ranking indexes. */
    private void addActiveRegistration(TimingData registration, TeamId teamId) {

        failIfIllegalState(registration.effectiveTime().isAbsolute(), "active registration time must be absolute");

        RegistrationId registrationId = registrationId(registration);
        ActiveRegistration activeRegistration = new ActiveRegistration(registration, registrationId, teamId);
        activeRegistrationsBySequence.put(registration.sequenceNumber(), activeRegistration);
        activeRegistrationsByRegistrationId
                .computeIfAbsent(registrationId, ignored -> new HashMap<>())
                .put(registration.sequenceNumber(), activeRegistration);
        addToTeam(activeRegistration);
    }

    /** Removes one active registration activeRegistration from all ranking indexes. */
    private void removeActiveRegistration(long sequenceNumber) {

        ActiveRegistration activeRegistration = activeRegistrationsBySequence.remove(sequenceNumber);
        failIfIllegalState(activeRegistration != null, "missing team-ranking activeRegistration for sequence %s", sequenceNumber);

        removeFromTeam(activeRegistration);

        Map<Long, ActiveRegistration> registrations = activeRegistrationsByRegistrationId.get(activeRegistration.registrationId);
        registrations.remove(sequenceNumber);
        if (registrations.isEmpty()) {
            activeRegistrationsByRegistrationId.remove(activeRegistration.registrationId);
        }
    }

    /** Adds one activeRegistration to its current location/team ordered set. */
    private void addToTeam(ActiveRegistration activeRegistration) {

        if (activeRegistration.teamId != null) {
            activeRegistrationsByLocationAndTeam
                    .computeIfAbsent(activeRegistration.registration.locationId(), ignored -> new HashMap<>())
                    .computeIfAbsent(activeRegistration.teamId, ignored -> new TreeSet<>(REGISTRATION_ORDER))
                    .add(activeRegistration);
        }
    }

    /** Removes one activeRegistration from its current location/team ordered set. */
    private void removeFromTeam(ActiveRegistration activeRegistration) {

        if (activeRegistration.teamId != null) {
            LocationId locationId = activeRegistration.registration.locationId();
            Map<TeamId, NavigableSet<ActiveRegistration>> teams = activeRegistrationsByLocationAndTeam.get(locationId);
            NavigableSet<ActiveRegistration> registrations = teams.get(activeRegistration.teamId);
            registrations.remove(activeRegistration);

            if (registrations.isEmpty()) {
                teams.remove(activeRegistration.teamId);
            }
            if (teams.isEmpty()) {
                activeRegistrationsByLocationAndTeam.remove(locationId);
            }
        }
    }

    /** Visits competition-ranked team rows after bounded sorting. */
    private static int visitRanked(List<StageTimingRecord> ordered, Consumer<TeamRank> visitor) {

        Duration previousElapsed = null;
        int rank = 0;
        for (int index = 0; index < ordered.size(); index++) {
            StageTimingRecord timingRecord = ordered.get(index);
            if (previousElapsed == null || !previousElapsed.equals(timingRecord.elapsed())) {
                rank = index + 1;
                previousElapsed = timingRecord.elapsed();
            }
            visitor.accept(new TeamRank(rank, timingRecord));
        }
        return ordered.size();
    }

    /** Extracts RegistrationId from one active registration record. */
    private static RegistrationId registrationId(TimingData data) {

        RegistrationId registrationId = ActiveRegistrations.registrationIdOrNull(data);
        failIfIllegalState(registrationId != null, "TimingData is not a registration");
        return registrationId;
    }

    /** One active registration indexed for incremental team selection. */
    private static final class ActiveRegistration {
        private final TimingData registration;
        private final RegistrationId registrationId;
        private TeamId teamId;

        private ActiveRegistration(TimingData registration, RegistrationId registrationId, TeamId teamId) {

            this.registration = registration;
            this.registrationId = registrationId;
            this.teamId = teamId;
        }
    }
}
