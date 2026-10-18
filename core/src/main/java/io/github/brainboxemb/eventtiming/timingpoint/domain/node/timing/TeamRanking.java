package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

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
 * Maintains the active registration candidates used for team ranking.
 *
 * <p>Candidate selection is maintained incrementally. Each location/team keeps its active
 * registrations ordered by finish time, so the selected registration is always the first
 * candidate. Ranking queries therefore inspect one selected registration per team instead
 * of scanning all active registrations.</p>
 */
public final class TeamRanking {
    private static final Comparator<Candidate> CANDIDATE_ORDER =
            Comparator.comparing((Candidate value) -> value.registration.effectiveTime().instant())
                    .thenComparingLong(value -> value.registration.sequenceNumber());

    private static final Comparator<StageTime> FASTEST_TEAM_FIRST =
            Comparator.comparing(StageTime::elapsed)
                    .thenComparing(value -> value.teamId().value());

    private final StageTiming stageTiming;
    private final Map<Long, Candidate> candidatesBySequence = new HashMap<>();
    private final Map<RegistrationId, Map<Long, Candidate>> candidatesByRegistrationId = new HashMap<>();
    private final Map<LocationId, Map<TeamId, NavigableSet<Candidate>>> candidatesByLocationAndTeam =
            new HashMap<>();

    /** Creates the ranking index over the shared StageTiming resolver. */
    public TeamRanking(StageTiming stageTiming) {

        failIfInvalidArgument(stageTiming != null, "stageTiming must not be null");
        this.stageTiming = stageTiming;
    }

    /** Clears all active ranking candidates before LogBook reconstruction. */
    public void reset() {

        candidatesBySequence.clear();
        candidatesByRegistrationId.clear();
        candidatesByLocationAndTeam.clear();
    }

    /** Applies one actual active-registration ADD or matched REV. */
    public void apply(ActiveRegistrations.Change change) {

        if (change != null) {
            if (change.action() == ActiveRegistrations.Change.Action.ADDED) {
                addCandidate(change.registration(), change.teamId());
            } else {
                removeCandidate(change.registration().sequenceNumber());
            }
        }
    }

    /** Moves only the active candidates for one RegistrationId to its current team. */
    public void teamAssignmentChanged(RegistrationId registrationId, TeamId teamId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");

        Map<Long, Candidate> registrations = candidatesByRegistrationId.get(registrationId);
        if (registrations != null) {
            for (Candidate candidate : registrations.values()) {
                if (!Objects.equals(candidate.teamId, teamId)) {
                    removeFromTeam(candidate);
                    candidate.teamId = teamId;
                    addToTeam(candidate);
                }
            }
        }
    }

    /**
     * Visits at most {@code limit} fastest teams at one location.
     *
     * <p>Only the currently selected registration for each team is resolved to StageTime.
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

        Map<TeamId, NavigableSet<Candidate>> teams = candidatesByLocationAndTeam.get(locationId);
        if (teams == null) {
            return 0;
        }

        PriorityQueue<StageTime> fastest =
                new PriorityQueue<StageTime>(limit, FASTEST_TEAM_FIRST.reversed());

        for (Map.Entry<TeamId, NavigableSet<Candidate>> entry : teams.entrySet()) {
            Candidate candidate = entry.getValue().first();
            StageTime stageTime =
                    stageTiming.resolve(candidate.registration, entry.getKey(), raceTimeZone);
            if (stageTime != null) {
                fastest.offer(stageTime);
                if (fastest.size() > limit) {
                    fastest.poll();
                }
            }
        }

        List<StageTime> ordered = new ArrayList<StageTime>(fastest);
        ordered.sort(FASTEST_TEAM_FIRST);
        return visitRanked(ordered, visitor);
    }

    /** Adds one active registration candidate to all ranking indexes. */
    private void addCandidate(TimingData registration, TeamId teamId) {

        failIfIllegalState(registration.effectiveTime().isAbsolute(), "active registration time must be absolute");

        RegistrationId registrationId = registrationId(registration);
        Candidate candidate = new Candidate(registration, registrationId, teamId);
        candidatesBySequence.put(registration.sequenceNumber(), candidate);
        candidatesByRegistrationId
                .computeIfAbsent(registrationId, ignored -> new HashMap<>())
                .put(registration.sequenceNumber(), candidate);
        addToTeam(candidate);
    }

    /** Removes one active registration candidate from all ranking indexes. */
    private void removeCandidate(long sequenceNumber) {

        Candidate candidate = candidatesBySequence.remove(sequenceNumber);
        failIfIllegalState(candidate != null, "missing team-ranking candidate for sequence %s", sequenceNumber);

        removeFromTeam(candidate);

        Map<Long, Candidate> registrations = candidatesByRegistrationId.get(candidate.registrationId);
        registrations.remove(sequenceNumber);
        if (registrations.isEmpty()) {
            candidatesByRegistrationId.remove(candidate.registrationId);
        }
    }

    /** Adds one candidate to its current location/team ordered set. */
    private void addToTeam(Candidate candidate) {

        if (candidate.teamId != null) {
            candidatesByLocationAndTeam
                    .computeIfAbsent(candidate.registration.locationId(), ignored -> new HashMap<>())
                    .computeIfAbsent(candidate.teamId, ignored -> new TreeSet<>(CANDIDATE_ORDER))
                    .add(candidate);
        }
    }

    /** Removes one candidate from its current location/team ordered set. */
    private void removeFromTeam(Candidate candidate) {

        if (candidate.teamId != null) {
            LocationId locationId = candidate.registration.locationId();
            Map<TeamId, NavigableSet<Candidate>> teams = candidatesByLocationAndTeam.get(locationId);
            NavigableSet<Candidate> registrations = teams.get(candidate.teamId);
            registrations.remove(candidate);

            if (registrations.isEmpty()) {
                teams.remove(candidate.teamId);
            }
            if (teams.isEmpty()) {
                candidatesByLocationAndTeam.remove(locationId);
            }
        }
    }

    /** Visits competition-ranked team rows after bounded sorting. */
    private static int visitRanked(List<StageTime> ordered, Consumer<TeamRank> visitor) {

        Duration previousElapsed = null;
        int rank = 0;
        for (int index = 0; index < ordered.size(); index++) {
            StageTime stageTime = ordered.get(index);
            if (previousElapsed == null || !previousElapsed.equals(stageTime.elapsed())) {
                rank = index + 1;
                previousElapsed = stageTime.elapsed();
            }
            visitor.accept(new TeamRank(rank, stageTime));
        }
        return ordered.size();
    }

    /** Extracts RegistrationId from one active registration record. */
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

    /** One active registration indexed for incremental team selection. */
    private static final class Candidate {
        private final TimingData registration;
        private final RegistrationId registrationId;
        private TeamId teamId;

        private Candidate(TimingData registration, RegistrationId registrationId, TeamId teamId) {

            this.registration = registration;
            this.registrationId = registrationId;
            this.teamId = teamId;
        }
    }
}
