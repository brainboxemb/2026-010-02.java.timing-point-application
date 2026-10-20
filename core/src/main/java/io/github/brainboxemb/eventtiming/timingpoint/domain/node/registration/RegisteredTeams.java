package io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Retains the current unique registered-team membership for each WP.
 *
 * <p>This is the second Task Lane-owned registration projection. ActiveRegistrations reports
 * actual ADD/REV validity transitions; NodeService resolves the affected RegistrationId to the
 * current TeamId and applies that fact here. Live RACE_DATA assignment changes are applied
 * separately through {@link #teamAssignmentChanged(RegistrationId, TeamId)}.</p>
 *
 * <p>A team is present at a WP while at least one valid registration occurrence at that WP
 * resolves to that team. Repeated passages therefore increase an internal occurrence count but
 * do not create duplicate team entries. A team membership ADDED event is emitted only on a
 * zero-to-nonzero transition and REMOVED only on nonzero-to-zero. An unresolved RegistrationId
 * is retained for later reassignment but contributes to no team set until a TeamId is known.</p>
 *
 * <p>The complete team visitor is the smart-display synchronization source. Membership is a set:
 * visitation order has no domain meaning and no temporary ordered list is created. CAN encoding,
 * burst acknowledgement and display synchronization state belong to the display transport, not
 * to this class.</p>
 */
public final class RegisteredTeams {
    private final Map<RegistrationId, RegistrationState> registrationsById = new HashMap<>();
    private final Map<LocationId, Map<TeamId, Integer>> teamsByLocation = new HashMap<>();
    private final Event<Change> changedEvent = new Event<>();

    /**
     * Incremental unique-team membership transitions.
     *
     * <p>Delivery is synchronous and ordered with the Task Lane mutation that caused the
     * transition. Repeated registrations that leave membership present emit nothing.</p>
     */
    public final EventSource<Change> CHANGED = changedEvent;

    /**
     * Clears all reconstructed registration/team counts before a full LogBook replay.
     *
     * <p>Reset emits no REMOVED events: it is reconstruction housekeeping, not a sequence of
     * domain removals. A display that lost synchronization must obtain a new full membership
     * burst after reconstruction.</p>
     */
    public void reset() {

        registrationsById.clear();
        teamsByLocation.clear();
    }

    /**
     * Applies one actual registration validity transition from ActiveRegistrations.
     *
     * <p>A null Change means the source TimingData did not alter active registration state and is
     * intentionally ignored. The supplied TeamId is the current resolution for an ADD; it may be
     * null when the RegistrationId is not assigned yet.</p>
     */
    public void apply(ActiveRegistrations.Change change, TeamId teamId) {

        if (change != null) {
            if (change.action() == ActiveRegistrations.Change.Action.ADDED) {
                add(change.registrationId(), change.locationId(), teamId);
            } else {
                remove(change.registrationId(), change.locationId());
            }
        }
    }

    /**
     * Reaccounts every active occurrence after the TeamId assignment for one RegistrationId changes.
     *
     * <p>The existing occurrence counts stay attached to the RegistrationId. Only their team
     * membership contribution moves, which may emit one REMOVED and/or ADDED transition per WP.</p>
     */
    public void teamAssignmentChanged(RegistrationId registrationId, TeamId teamId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");

        RegistrationState registration = registrationsById.get(registrationId);
        if (registration != null && !Objects.equals(registration.teamId, teamId)) {
            moveCounts(registration, registration.teamId, teamId);
            registration.teamId = teamId;
        }
    }

    /** Returns the number of distinct currently resolved teams present at one WP. */
    public int uniqueTeamCount(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");

        Map<TeamId, Integer> teams = teamsByLocation.get(locationId);
        return teams == null ? 0 : teams.size();
    }

    /**
     * Visits the full current unique-team set for a display synchronization burst.
     *
     * <p>Team order has no domain meaning. The visitor receives the retained set directly
     * without constructing or sorting a temporary snapshot list.</p>
     */
    public int visit(LocationId locationId, Consumer<TeamId> visitor) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        Map<TeamId, Integer> teams = teamsByLocation.get(locationId);
        if (teams == null) {
            return 0;
        }

        for (TeamId teamId : teams.keySet()) {
            visitor.accept(teamId);
        }
        return teams.size();
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
            changedEvent.emit(new Change(Change.Action.ADDED, locationId, teamId));
        } else if (previous > 0 && next == 0) {
            changedEvent.emit(new Change(Change.Action.REMOVED, locationId, teamId));
        }
    }

    /**
     * Retained accounting for one RegistrationId.
     *
     * <p>Occurrences are counted per WP so a later assignment change can move all existing
     * contributions without replaying ActiveRegistrations.</p>
     */
    private static final class RegistrationState {
        private final Map<LocationId, Integer> occurrencesByLocation = new HashMap<>();
        private TeamId teamId;
    }

    /** One immutable unique-team membership transition for one WP. */
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

        /** Returns whether the TeamId entered or left the WP membership set. */
        public Action action() {
            return action;
        }

        /** Returns the WP whose unique-team membership changed. */
        public LocationId locationId() {
            return locationId;
        }

        /** Returns the team that entered or left the WP membership set. */
        public TeamId teamId() {
            return teamId;
        }
    }
}
