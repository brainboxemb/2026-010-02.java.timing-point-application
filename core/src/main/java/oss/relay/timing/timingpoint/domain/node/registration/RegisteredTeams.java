package oss.relay.timing.timingpoint.domain.node.registration;

import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingpoint.platform.events.Event;
import oss.relay.timing.timingpoint.platform.events.EventSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfIllegalState;
import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Retains unique registered-team membership for the currently open WP.
 *
 * <p>ActiveRegistrations supplies actual ADD/REV validity transitions after filtering LogBook
 * history to the active LocationId. This projection therefore needs no LocationId key of its own.
 * It counts active occurrences per RegistrationId and maps those counts through the current
 * RaceData TeamId assignment.</p>
 *
 * <p>A team is present while at least one valid occurrence resolves to that TeamId. Repeated
 * passages change only occurrence counts. Live membership ADDED is emitted only on a zero-to-
 * nonzero transition and REMOVED only on nonzero-to-zero. An unresolved RegistrationId remains
 * counted so a later assignment can contribute all of its existing occurrences.</p>
 *
 * <p>LogBook reconstruction is deliberately silent: beginRebuild clears the previous WP state
 * and suppresses incremental events until completeRebuild. A display synchronizing after OPEN
 * obtains the complete set through visit(); only later live mutations produce +/- events.</p>
 */
public final class RegisteredTeams {
    private final Map<RegistrationId, RegistrationState> registrationsById = new HashMap<>();
    private final Map<TeamId, Integer> teamCounts = new HashMap<>();
    private final Event<Change> changedEvent = new Event<>();

    private boolean active;
    private boolean publishChanges;

    /**
     * Incremental membership transitions for the currently open WP.
     *
     * <p>Delivery is synchronous and ordered with the Task Lane mutation that caused it.</p>
     */
    public final EventSource<Change> CHANGED = changedEvent;

    /** Starts a silent reconstruction for one newly opened WP. */
    public void beginRebuild() {

        active = true;
        publishChanges = false;
        registrationsById.clear();
        teamCounts.clear();
    }

    /** Marks the reconstructed set ready so later live mutations publish incremental changes. */
    public void completeRebuild() {

        failIfIllegalState(active, "registered-team projection is not active");
        publishChanges = true;
    }

    /** Deactivates the projection on CLOSE without publishing synthetic removals. */
    public void deactivate() {

        active = false;
        publishChanges = false;
        registrationsById.clear();
        teamCounts.clear();
    }

    /** Applies one registration validity transition for the active WP. */
    public void apply(ActiveRegistrations.Change change, TeamId teamId) {

        failIfIllegalState(active, "registered-team projection is not active");
        if (change != null) {
            if (change.action() == ActiveRegistrations.Change.Action.ADDED) {
                add(change.registrationId(), teamId);
            } else {
                remove(change.registrationId());
            }
        }
    }

    /**
     * Reaccounts all active occurrences after one RegistrationId's TeamId assignment changes.
     *
     * <p>When no WP is active there is no registration projection to update, so the change is
     * intentionally ignored. During rebuild the counts move but incremental events stay
     * suppressed.</p>
     */
    public void teamAssignmentChanged(RegistrationId registrationId, TeamId teamId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");

        if (active) {
            RegistrationState registration = registrationsById.get(registrationId);
            if (registration != null && !Objects.equals(registration.teamId, teamId)) {
                moveCount(registration, registration.teamId, teamId);
                registration.teamId = teamId;
            }
        }
    }

    /** Returns the number of distinct currently resolved teams in the open WP. */
    public int uniqueTeamCount() {

        failIfIllegalState(active, "registered-team projection is not active");
        return teamCounts.size();
    }

    /**
     * Visits the complete current unique-team set for a display synchronization burst.
     *
     * <p>Team order has no domain meaning. The retained set is streamed directly without a
     * temporary snapshot or sort.</p>
     */
    public int visit(Consumer<TeamId> visitor) {

        failIfIllegalState(active, "registered-team projection is not active");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        for (TeamId teamId : teamCounts.keySet()) {
            visitor.accept(teamId);
        }
        return teamCounts.size();
    }

    /** Counts one valid occurrence, applying the current assignment before adding it. */
    private void add(RegistrationId registrationId, TeamId teamId) {

        RegistrationState registration =
                registrationsById.computeIfAbsent(registrationId, ignored -> new RegistrationState());
        if (!Objects.equals(registration.teamId, teamId)) {
            moveCount(registration, registration.teamId, teamId);
            registration.teamId = teamId;
        }

        registration.occurrences++;
        adjustCount(teamId, 1);
    }

    /** Removes one previously accepted occurrence; unmatched REV records never arrive here. */
    private void remove(RegistrationId registrationId) {

        RegistrationState registration = registrationsById.get(registrationId);
        failIfIllegalState(registration != null, "missing registered team state for %s", registrationId);
        failIfIllegalState(registration.occurrences >= 1, "missing registration occurrence for %s", registrationId);

        registration.occurrences--;
        adjustCount(registration.teamId, -1);
        if (registration.occurrences == 0) {
            registrationsById.remove(registrationId);
        }
    }

    /** Moves every active occurrence from the previous assignment to the new assignment. */
    private void moveCount(RegistrationState registration, TeamId previousTeam, TeamId newTeam) {

        if (registration.occurrences > 0) {
            adjustCount(previousTeam, -registration.occurrences);
            adjustCount(newTeam, registration.occurrences);
        }
    }

    /** Updates one team count and emits only real live membership transitions. */
    private void adjustCount(TeamId teamId, int change) {

        if (teamId == null) {
            return;
        }

        int previous = teamCounts.getOrDefault(teamId, 0);
        int next = previous + change;
        failIfIllegalState(next >= 0, "registered-team count must not be negative");

        if (next == 0) {
            teamCounts.remove(teamId);
        } else {
            teamCounts.put(teamId, next);
        }

        if (publishChanges && previous == 0 && next > 0) {
            changedEvent.emit(new Change(Change.Action.ADDED, teamId));
        } else if (publishChanges && previous > 0 && next == 0) {
            changedEvent.emit(new Change(Change.Action.REMOVED, teamId));
        }
    }

    /** Retained occurrence count and current assignment for one RegistrationId. */
    private static final class RegistrationState {
        private int occurrences;
        private TeamId teamId;
    }

    /** One immutable live unique-team membership transition for the active WP. */
    public static final class Change {
        public enum Action {
            ADDED,
            REMOVED
        }

        private final Action action;
        private final TeamId teamId;

        private Change(Action action, TeamId teamId) {

            this.action = action;
            this.teamId = teamId;
        }

        /** Returns whether the TeamId entered or left the active WP set. */
        public Action action() {

            return action;
        }

        /** Returns the team that entered or left the active WP set. */
        public TeamId teamId() {

            return teamId;
        }
    }
}
