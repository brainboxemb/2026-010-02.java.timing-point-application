package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Active registration facts and incremental unique-team counts for a node.
 *
 * <p>ADD records remain available for stage timing. REV matches one active ADD
 * by registration ID, location and effective time. Only an actually matched
 * REV changes the counts. Counts are grouped by location and also maintained
 * across all locations for the upstream protocol.</p>
 *
 * <p>Team assignments can change independently of registration records. The
 * owning NodeService updates the affected registration IDs after accepting
 * race-data mutations. All access is confined to its Task Lane.</p>
 */
public final class ActiveRegistrations {
    private final List<TimingData> active = new ArrayList<>();
    private final Map<RegistrationId, Map<LocationId, Integer>> occurrences = new HashMap<>();
    private final Map<RegistrationId, TeamId> resolvedTeams = new HashMap<>();
    private final Map<LocationId, Map<TeamId, Integer>> teamsByLocation = new HashMap<>();
    private final Map<TeamId, Integer> teamsAcrossLocations = new HashMap<>();

    /** Clears active facts and all derived counts before history reconstruction. */
    public void reset() {
        active.clear();
        occurrences.clear();
        resolvedTeams.clear();
        teamsByLocation.clear();
        teamsAcrossLocations.clear();
    }

    /** Applies a record without team resolution, useful for standalone folds. */
    public void apply(TimingData data) {
        apply(data, registrationId -> null);
    }

    /** Applies a committed record, updating counters only for actual changes. */
    public void apply(TimingData data, Function<RegistrationId, TeamId> teamResolver) {
        failIfInvalidArgument(data != null, "data must not be null");
        failIfInvalidArgument(teamResolver != null, "teamResolver must not be null");

        RegistrationId registrationId = registrationIdOrNull(data);
        if (registrationId == null) {
            return;
        }

        if (registrationAction(data) == TimingData.RegistrationAction.ADD) {
            teamAssignmentChanged(registrationId, teamResolver.apply(registrationId));
            active.add(data);
            addOccurrence(registrationId, data.locationId());
        } else if (registrationAction(data) == TimingData.RegistrationAction.REV) {
            TimingData matched = removeMatchingRegistration(data);
            if (matched != null) {
                removeOccurrence(registrationId, matched.locationId());
            }
        }
    }

    /** Updates one registration's resolved team without rescanning registrations. */
    public void teamAssignmentChanged(RegistrationId registrationId, TeamId teamId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        TeamId previous = resolvedTeams.get(registrationId);
        if (Objects.equals(previous, teamId)) {
            return;
        }

        Map<LocationId, Integer> locations = occurrences.get(registrationId);
        if (locations != null) {
            for (Map.Entry<LocationId, Integer> entry : locations.entrySet()) {
                if (previous != null) {
                    adjustCount(entry.getKey(), previous, -entry.getValue());
                }
                if (teamId != null) {
                    adjustCount(entry.getKey(), teamId, entry.getValue());
                }
            }
        }
        if (teamId == null) {
            resolvedTeams.remove(registrationId);
        } else {
            resolvedTeams.put(registrationId, teamId);
        }
    }

    /** Returns the maintained number of unique teams at one location. */
    public int uniqueTeamCount(LocationId locationId) {
        failIfInvalidArgument(locationId != null, "locationId must not be null");
        Map<TeamId, Integer> teams = teamsByLocation.get(locationId);
        return teams == null ? 0 : teams.size();
    }

    /** Returns the maintained unique-team count over the entire node. */
    public int uniqueTeamCount() {
        return teamsAcrossLocations.size();
    }

    /** Returns the number of registration IDs with at least one active ADD. */
    public int activeRegistrationIdCount() {
        return occurrences.size();
    }

    /** Exposes active facts for same-lane stage timing, without exposing mutations. */
    Iterable<TimingData> values() {
        return Collections.unmodifiableList(active);
    }

    private void addOccurrence(RegistrationId registrationId, LocationId locationId) {
        Map<LocationId, Integer> locations =
                occurrences.computeIfAbsent(registrationId, ignored -> new HashMap<>());
        locations.merge(locationId, 1, Integer::sum);
        TeamId teamId = resolvedTeams.get(registrationId);
        if (teamId != null) {
            adjustCount(locationId, teamId, 1);
        }
    }

    private void removeOccurrence(RegistrationId registrationId, LocationId locationId) {
        Map<LocationId, Integer> locations = occurrences.get(registrationId);
        int remaining = locations.get(locationId) - 1;
        if (remaining == 0) {
            locations.remove(locationId);
        } else {
            locations.put(locationId, remaining);
        }
        TeamId teamId = resolvedTeams.get(registrationId);
        if (teamId != null) {
            adjustCount(locationId, teamId, -1);
        }
        if (locations.isEmpty()) {
            occurrences.remove(registrationId);
            resolvedTeams.remove(registrationId);
        }
    }

    private void adjustCount(LocationId locationId, TeamId teamId, int change) {
        Map<TeamId, Integer> locationTeams =
                teamsByLocation.computeIfAbsent(locationId, ignored -> new HashMap<>());
        adjust(locationTeams, teamId, change);
        adjust(teamsAcrossLocations, teamId, change);
        if (locationTeams.isEmpty()) {
            teamsByLocation.remove(locationId);
        }
    }

    private static void adjust(Map<TeamId, Integer> counts, TeamId teamId, int change) {
        int next = counts.getOrDefault(teamId, 0) + change;
        if (next == 0) {
            counts.remove(teamId);
        } else {
            counts.put(teamId, next);
        }
    }

    /** Removes exactly one ADD that matches the REV identity. */
    private TimingData removeMatchingRegistration(TimingData revoke) {
        RegistrationId revokeId = registrationIdOrNull(revoke);
        for (int index = active.size() - 1; index >= 0; index--) {
            TimingData candidate = active.get(index);
            if (revokeId.equals(registrationIdOrNull(candidate))
                    && Objects.equals(revoke.locationId(), candidate.locationId())
                    && revoke.effectiveTime().equals(candidate.effectiveTime())) {
                active.remove(index);
                return candidate;
            }
        }
        return null;
    }

    private static RegistrationId registrationIdOrNull(TimingData data) {
        if (data instanceof TimingData.AutomaticRegistration) {
            return ((TimingData.AutomaticRegistration) data).registrationId();
        }
        if (data instanceof TimingData.ManualRegistration) {
            return ((TimingData.ManualRegistration) data).registrationId();
        }
        return null;
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
}
