package oss.relay.timing.timingpoint.domain.node.racedata;

import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Package-private live RegistrationId-to-TeamId assignment state owned by RaceDataState.
 *
 * <p>NodeService validates a complete bounded TEAM_ASSIGNMENTS message before mutating this
 * state. This type is an implementation detail; callers outside the race-data package resolve
 * teams through RaceDataState rather than mutating the assignment map directly.</p>
 */
final class TeamAssignments {
    private final Map<RegistrationId, TeamId> assignments;

    /** Creates empty live assignment state. */
    TeamAssignments() {

        this.assignments = new LinkedHashMap<RegistrationId, TeamId>();
    }

    /** Returns the live TeamId assignment for one RegistrationId, or null when absent. */
    TeamId teamIdFor(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return assignments.get(registrationId);
    }

    /**
     * Finds the current live RegistrationId owning one TeamId, or null when unassigned.
     *
     * <p>The bounded TEAM_ASSIGNMENTS validator uses this targeted scan instead of retaining a
     * second reverse index solely for validation.</p>
     */
    RegistrationId registrationIdFor(TeamId teamId) {

        failIfInvalidArgument(teamId != null, "teamId must not be null");
        for (Map.Entry<RegistrationId, TeamId> entry : assignments.entrySet()) {
            if (teamId.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Sets the live TeamId assignment for one RegistrationId. */
    void assign(RegistrationId registrationId, TeamId teamId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(teamId != null, "teamId must not be null");
        assignments.put(registrationId, teamId);
    }

    /** Removes any live assignment for the RegistrationId. */
    void remove(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        assignments.remove(registrationId);
    }

    /** Visits all live Central-owned assignments in retained order and returns their count. */
    int visit(BiConsumer<RegistrationId, TeamId> visitor) {

        failIfInvalidArgument(visitor != null, "visitor must not be null");
        for (Map.Entry<RegistrationId, TeamId> entry : assignments.entrySet()) {
            visitor.accept(entry.getKey(), entry.getValue());
        }
        return assignments.size();
    }
}
