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
 * Keeps active registrations and incremental unique-team counts.
 *
 * <p>ADD records remain available for stage timing. REV removes one matching active ADD
 * using registration ID, location and effective time. Only a matched REV changes counts.
 * Unique-team counts are maintained per location and across the complete node.</p>
 *
 * <p>Team assignments can change independently from TimingData. NodeService forwards those
 * changes on the Task Lane so counts can be updated without rescanning registrations.</p>
 */
public final class ActiveRegistrations {
    private final List<TimingData> active = new ArrayList<>();
    private final Map<RegistrationId, Map<LocationId, Integer>> occurrences = new HashMap<>();
    private final Map<RegistrationId, TeamId> resolvedTeams = new HashMap<>();
    private final Map<LocationId, Map<TeamId, Integer>> teamsByLocation = new HashMap<>();
    private final Map<TeamId, Integer> teamsAcrossLocations = new HashMap<>();

    /** Clears active registrations and all derived counts before TimingData recovery. */
    public void reset() {

        active.clear();
        occurrences.clear();
        resolvedTeams.clear();
        teamsByLocation.clear();
        teamsAcrossLocations.clear();
    }

    /** Applies one TimingData record without resolving a team. */
    public void apply(TimingData data) {

        apply(data, registrationId -> null);
    }

    /** Applies one TimingData record and updates maintained counts. */
    public void apply(TimingData data, Function<RegistrationId, TeamId> teamResolver) {

        failIfInvalidArgument(data != null, "data must not be null");
        failIfInvalidArgument(teamResolver != null, "teamResolver must not be null");

        RegistrationId registrationId = registrationIdOrNull(data);
        if (registrationId != null) {
            TimingData.RegistrationAction action = registrationAction(data);
            if (action == TimingData.RegistrationAction.ADD) {
                active.add(data);
                addOccurrence(registrationId, data.locationId());
                teamAssignmentChanged(registrationId, teamResolver.apply(registrationId));
            } else if (action == TimingData.RegistrationAction.REV) {
                TimingData activeRegistration = removeMatchingRegistration(data);
                if (activeRegistration != null) {
                    removeOccurrence(registrationId, activeRegistration.locationId());
                }
            }
        }
    }

    /** Updates one registration's resolved team and its maintained counts. */
    public void teamAssignmentChanged(RegistrationId registrationId, TeamId teamId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");

        Map<LocationId, Integer> locations = occurrences.get(registrationId);
        TeamId previousTeam = resolvedTeams.get(registrationId);

        if (locations == null) {
            resolvedTeams.remove(registrationId);
        } else if (!Objects.equals(previousTeam, teamId)) {
            for (Map.Entry<LocationId, Integer> entry : locations.entrySet()) {
                if (previousTeam != null) {
                    adjustCount(entry.getKey(), previousTeam, -entry.getValue());
                }
                if (teamId != null) {
                    adjustCount(entry.getKey(), teamId, entry.getValue());
                }
            }

            if (teamId == null) {
                resolvedTeams.remove(registrationId);
            } else {
                resolvedTeams.put(registrationId, teamId);
            }
        }
    }

    /** Returns the maintained number of unique teams at one location. */
    public int uniqueTeamCount(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");

        Map<TeamId, Integer> teams = teamsByLocation.get(locationId);
        return teams == null ? 0 : teams.size();
    }

    /** Returns the maintained number of unique teams across all locations. */
    public int uniqueTeamCount() {

        return teamsAcrossLocations.size();
    }

    /** Returns active registration records for same-lane stage timing. */
    Iterable<TimingData> values() {

        return Collections.unmodifiableList(active);
    }

    /** Adds one active occurrence at the supplied location. */
    private void addOccurrence(RegistrationId registrationId, LocationId locationId) {

        Map<LocationId, Integer> locations = occurrences.computeIfAbsent(registrationId, ignored -> new HashMap<>());
        locations.merge(locationId, 1, Integer::sum);

        TeamId teamId = resolvedTeams.get(registrationId);
        if (teamId != null) {
            adjustCount(locationId, teamId, 1);
        }
    }

    /** Removes one active occurrence at the supplied location. */
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

    /** Adjusts one team's reference count at a location and across the node. */
    private void adjustCount(LocationId locationId, TeamId teamId, int change) {

        Map<TeamId, Integer> locationTeams = teamsByLocation.computeIfAbsent(locationId, ignored -> new HashMap<>());
        adjust(locationTeams, teamId, change);
        adjust(teamsAcrossLocations, teamId, change);

        if (locationTeams.isEmpty()) {
            teamsByLocation.remove(locationId);
        }
    }

    /** Adjusts one reference count and removes the entry when it reaches zero. */
    private static void adjust(Map<TeamId, Integer> counts, TeamId teamId, int change) {

        int next = counts.getOrDefault(teamId, 0) + change;
        if (next == 0) {
            counts.remove(teamId);
        } else {
            counts.put(teamId, next);
        }
    }

    /** Removes exactly one active ADD matching the REV identity. */
    private TimingData removeMatchingRegistration(TimingData revoke) {

        RegistrationId revokeId = registrationIdOrNull(revoke);
        for (int index = active.size() - 1; index >= 0; index--) {
            TimingData activeRegistration = active.get(index);
            boolean sameRegistration = revokeId.equals(registrationIdOrNull(activeRegistration));
            boolean sameLocation = Objects.equals(revoke.locationId(), activeRegistration.locationId());
            boolean sameTime = revoke.effectiveTime().equals(activeRegistration.effectiveTime());

            if (sameRegistration && sameLocation && sameTime) {
                active.remove(index);
                return activeRegistration;
            }
        }
        return null;
    }

    /** Returns the RegistrationId for a registration record, otherwise null. */
    private static RegistrationId registrationIdOrNull(TimingData data) {

        RegistrationId registrationId = null;
        if (data instanceof TimingData.AutomaticRegistration) {
            registrationId = ((TimingData.AutomaticRegistration) data).registrationId();
        } else if (data instanceof TimingData.ManualRegistration) {
            registrationId = ((TimingData.ManualRegistration) data).registrationId();
        }
        return registrationId;
    }

    /** Returns the ADD/REV action for a registration record, otherwise null. */
    private static TimingData.RegistrationAction registrationAction(TimingData data) {

        TimingData.RegistrationAction action = null;
        if (data instanceof TimingData.AutomaticRegistration) {
            action = ((TimingData.AutomaticRegistration) data).action();
        } else if (data instanceof TimingData.ManualRegistration) {
            action = ((TimingData.ManualRegistration) data).action();
        }
        return action;
    }
}
