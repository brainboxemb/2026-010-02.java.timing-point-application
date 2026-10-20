package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Maintains unique registered teams per WP, independently of the display's registration list.
 *
 * <p>Only a transition between zero and nonzero valid registrations emits a + or - change.
 * The full team list can be visited for a compact display synchronization burst.</p>
 */
public final class RegisteredTeams {
    private final Map<RegistrationId, RegistrationState> registrationsById = new HashMap<>();
    private final Map<LocationId, Map<TeamId, Integer>> teamsByLocation = new HashMap<>();
    private final Event<Change> changes = new Event<>();

    /** Exposes ordered + and - membership changes on the calling Task Lane. */
    public EventSource<Change> changes() {

        return changes;
    }

    /** Clears reconstructed counts; display consumers must request a new full burst after reset. */
    public void reset() {

        registrationsById.clear();
        teamsByLocation.clear();
    }

    /** Applies one actual registration ADD or matching REV from ActiveRegistrations. */
    public void apply(ActiveRegistrations.Change change, TeamId teamId) {

        if (change != null) {
            if (change.action() == ActiveRegistrations.Change.Action.ADDED) {
                add(change.registrationId(), change.locationId(), teamId);
            } else {
                remove(change.registrationId(), change.locationId());
            }
        }
    }

    /** Moves all active registrations for one registration ID to the currently assigned team. */
    public void teamAssignmentChanged(RegistrationId registrationId, TeamId teamId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");

        RegistrationState registration = registrationsById.get(registrationId);
        if (registration != null && !Objects.equals(registration.teamId, teamId)) {
            moveCounts(registration, registration.teamId, teamId);
            registration.teamId = teamId;
        }
    }

    /** Returns how many different teams have a valid registration at this WP. */
    public int uniqueTeamCount(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");

        Map<TeamId, Integer> teams = teamsByLocation.get(locationId);
        return teams == null ? 0 : teams.size();
    }

    /** Visits the full current unique-team set for a display synchronization burst. */
    public int visit(LocationId locationId, Consumer<TeamId> visitor) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        Map<TeamId, Integer> teams = teamsByLocation.get(locationId);
        if (teams == null) {
            return 0;
        }

        List<TeamId> ordered = new ArrayList<>(teams.keySet());
        ordered.sort(Comparator.comparing(TeamId::value));
        for (TeamId teamId : ordered) {
            visitor.accept(teamId);
        }
        return ordered.size();
    }

    /** Counts one valid occurrence, applying any newer assignment before adding it. */
    private void add(RegistrationId registrationId, LocationId locationId, TeamId teamId) {

        RegistrationState registration =
                registrationsById.computeIfAbsent(registrationId, ignored -> new RegistrationState());
        if (!Objects.equals(registration.teamId, teamId)) {
            moveCounts(registration, registration.teamId, teamId);
            registration.teamId = teamId;
        }

        registration.occurrencesByLocation.merge(locationId, 1, Integer::sum);
        adjustCount(locationId, teamId, 1);
    }

    /** Removes one previously accepted occurrence; unmatched REV records never arrive here. */
    private void remove(RegistrationId registrationId, LocationId locationId) {

        RegistrationState registration = registrationsById.get(registrationId);
        failIfIllegalState(registration != null, "missing registered team state for %s", registrationId);
        Integer count = registration.occurrencesByLocation.get(locationId);
        failIfIllegalState(count != null && count >= 1, "missing registration count at %s", locationId);

        if (count == 1) {
            registration.occurrencesByLocation.remove(locationId);
        } else {
            registration.occurrencesByLocation.put(locationId, count - 1);
        }
        adjustCount(locationId, registration.teamId, -1);

        if (registration.occurrencesByLocation.isEmpty()) {
            registrationsById.remove(registrationId);
        }
    }

    /** Reassigns every active occurrence, emitting only membership transitions. */
    private void moveCounts(RegistrationState registration, TeamId previousTeam, TeamId newTeam) {

        for (Map.Entry<LocationId, Integer> entry : registration.occurrencesByLocation.entrySet()) {
            adjustCount(entry.getKey(), previousTeam, -entry.getValue());
            adjustCount(entry.getKey(), newTeam, entry.getValue());
        }
    }

    /** Emits a change only when a team's presence at one WP changes. */
    private void adjustCount(LocationId locationId, TeamId teamId, int change) {

        if (teamId == null) {
            return;
        }

        Map<TeamId, Integer> teams =
                teamsByLocation.computeIfAbsent(locationId, ignored -> new HashMap<>());
        int previous = teams.getOrDefault(teamId, 0);
        int next = previous + change;
        failIfIllegalState(next >= 0, "registered-team count must not be negative");

        if (next == 0) {
            teams.remove(teamId);
        } else {
            teams.put(teamId, next);
        }
        if (teams.isEmpty()) {
            teamsByLocation.remove(locationId);
        }

        if (previous == 0 && next > 0) {
            changes.emit(new Change(Change.Action.ADDED, locationId, teamId));
        } else if (previous > 0 && next == 0) {
            changes.emit(new Change(Change.Action.REMOVED, locationId, teamId));
        }
    }

    /** One registration ID's active occurrences and currently resolved team. */
    private static final class RegistrationState {
        private final Map<LocationId, Integer> occurrencesByLocation = new HashMap<>();
        private TeamId teamId;
    }

    /** One immutable + or - team membership change for one WP. */
    public static final class Change {
        public enum Action {
            ADDED,
            REMOVED
        }

        private final Action action;
        private final LocationId locationId;
        private final TeamId teamId;

        private Change(Action action, LocationId locationId, TeamId teamId) {

            this.action = action;
            this.locationId = locationId;
            this.teamId = teamId;
        }

        public Action action() {
            return action;
        }

        public LocationId locationId() {
            return locationId;
        }

        public TeamId teamId() {
            return teamId;
        }
    }
}
