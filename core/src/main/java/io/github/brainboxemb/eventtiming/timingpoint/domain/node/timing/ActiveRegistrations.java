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
 * Keeps active registrations and unique-team counts per location.
 *
 * <p>ADD records remain available for StageTiming. REV removes one matching active ADD
 * using registration ID, location and effective time. Team counts are updated only when
 * the active registration state actually changes.</p>
 */
public final class ActiveRegistrations {
    private final List<TimingData> activeRecords = new ArrayList<>();
    private final Map<RegistrationId, RegistrationState> registrationsById = new HashMap<>();
    private final Map<LocationId, Map<TeamId, Integer>> teamsByLocation = new HashMap<>();

    /** Clears active registrations and derived location counts before TimingData recovery. */
    public void reset() {

        activeRecords.clear();
        registrationsById.clear();
        teamsByLocation.clear();
    }

    /** Applies one TimingData record and updates active registrations and location counts. */
    public void apply(TimingData data, Function<RegistrationId, TeamId> teamResolver) {

        failIfInvalidArgument(data != null, "data must not be null");
        failIfInvalidArgument(teamResolver != null, "teamResolver must not be null");

        RegistrationId registrationId = registrationIdOrNull(data);
        if (registrationId != null) {
            TimingData.RegistrationAction action = registrationAction(data);
            if (action == TimingData.RegistrationAction.ADD) {
                addRegistration(data, registrationId, teamResolver.apply(registrationId));
            } else if (action == TimingData.RegistrationAction.REV) {
                revokeRegistration(data, registrationId);
            }
        }
    }

    /** Updates the resolved team for all active occurrences of one registration. */
    public void teamAssignmentChanged(RegistrationId registrationId, TeamId teamId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");

        RegistrationState registration = registrationsById.get(registrationId);
        if (registration != null && !Objects.equals(registration.teamId, teamId)) {
            moveTeamCounts(registration, registration.teamId, teamId);
            registration.teamId = teamId;
        }
    }

    /** Returns the maintained number of unique teams at one location. */
    public int uniqueTeamCount(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");

        Map<TeamId, Integer> teams = teamsByLocation.get(locationId);
        return teams == null ? 0 : teams.size();
    }

    /** Returns active registration records for same-lane StageTiming reads. */
    Iterable<TimingData> values() {

        return Collections.unmodifiableList(activeRecords);
    }

    /** Adds one active registration occurrence and its resolved team reference. */
    private void addRegistration(TimingData data, RegistrationId registrationId, TeamId teamId) {

        activeRecords.add(data);

        RegistrationState registration =
                registrationsById.computeIfAbsent(registrationId, ignored -> new RegistrationState());
        registration.occurrencesByLocation.merge(data.locationId(), 1, Integer::sum);

        if (!Objects.equals(registration.teamId, teamId)) {
            moveTeamCounts(registration, registration.teamId, teamId);
            registration.teamId = teamId;
        } else if (teamId != null) {
            adjustTeamCount(data.locationId(), teamId, 1);
        }
    }

    /** Revokes exactly one active registration occurrence when its ADD identity matches. */
    private void revokeRegistration(TimingData revoke, RegistrationId registrationId) {

        TimingData activeRegistration = removeMatchingRegistration(revoke, registrationId);
        if (activeRegistration != null) {
            removeOccurrence(registrationId, activeRegistration.locationId());
        }
    }

    /** Removes one occurrence from the registration and location-team indexes. */
    private void removeOccurrence(RegistrationId registrationId, LocationId locationId) {

        RegistrationState registration = registrationsById.get(registrationId);
        int remaining = registration.occurrencesByLocation.get(locationId) - 1;

        if (remaining == 0) {
            registration.occurrencesByLocation.remove(locationId);
        } else {
            registration.occurrencesByLocation.put(locationId, remaining);
        }

        if (registration.teamId != null) {
            adjustTeamCount(locationId, registration.teamId, -1);
        }

        if (registration.occurrencesByLocation.isEmpty()) {
            registrationsById.remove(registrationId);
        }
    }

    /** Moves all active occurrences from one resolved team to another. */
    private void moveTeamCounts(RegistrationState registration, TeamId previousTeam, TeamId newTeam) {

        for (Map.Entry<LocationId, Integer> entry : registration.occurrencesByLocation.entrySet()) {
            if (previousTeam != null) {
                adjustTeamCount(entry.getKey(), previousTeam, -entry.getValue());
            }
            if (newTeam != null) {
                adjustTeamCount(entry.getKey(), newTeam, entry.getValue());
            }
        }
    }

    /** Adjusts one team's reference count at one location. */
    private void adjustTeamCount(LocationId locationId, TeamId teamId, int change) {

        Map<TeamId, Integer> teams =
                teamsByLocation.computeIfAbsent(locationId, ignored -> new HashMap<>());
        int next = teams.getOrDefault(teamId, 0) + change;

        if (next == 0) {
            teams.remove(teamId);
        } else {
            teams.put(teamId, next);
        }

        if (teams.isEmpty()) {
            teamsByLocation.remove(locationId);
        }
    }

    /** Removes one active ADD matching registration ID, location and effective time. */
    private TimingData removeMatchingRegistration(TimingData revoke, RegistrationId registrationId) {

        TimingData matchingRegistration = null;
        for (int index = activeRecords.size() - 1; index >= 0 && matchingRegistration == null; index--) {
            TimingData candidate = activeRecords.get(index);
            boolean sameRegistration = registrationId.equals(registrationIdOrNull(candidate));
            boolean sameLocation = Objects.equals(revoke.locationId(), candidate.locationId());
            boolean sameTime = revoke.effectiveTime().equals(candidate.effectiveTime());

            if (sameRegistration && sameLocation && sameTime) {
                matchingRegistration = activeRecords.remove(index);
            }
        }
        return matchingRegistration;
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

    /** Active occurrences and current team resolution for one RegistrationId. */
    private static final class RegistrationState {
        private final Map<LocationId, Integer> occurrencesByLocation = new HashMap<>();
        private TeamId teamId;
    }
}
