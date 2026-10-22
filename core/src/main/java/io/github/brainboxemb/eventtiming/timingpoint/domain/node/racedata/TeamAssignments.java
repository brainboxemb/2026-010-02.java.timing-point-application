package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Package-private live RegistrationId-to-TeamId assignment state owned by RaceDataState.
 *
 * <p>Copies are used to validate a complete RACE_DATA message before replacing live state.
 * This type is an implementation detail; callers outside the race-data package resolve teams
 * through RaceDataState rather than mutating the assignment map directly.</p>
 */
final class TeamAssignments {
    private final Map<RegistrationId, TeamId> assignments;

    /** Creates empty live assignment state. */
    TeamAssignments() {
        this.assignments = new LinkedHashMap<RegistrationId, TeamId>();
    }

    /** Creates an independent mutable copy from existing assignment values. */
    private TeamAssignments(Map<RegistrationId, TeamId> assignments) {
        this.assignments = new LinkedHashMap<RegistrationId, TeamId>(assignments);
    }

    /** Returns an independent mutable copy for transactional validation. */
    TeamAssignments copy() {
        return new TeamAssignments(assignments);
    }

    /** Returns the live TeamId assignment for one RegistrationId, or null when absent. */
    TeamId teamIdFor(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return assignments.get(registrationId);
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

    /** Visits all live RACE_DATA assignments in retained order and returns their count. */
    int visit(BiConsumer<RegistrationId, TeamId> visitor) {
        failIfInvalidArgument(visitor != null, "visitor must not be null");
        for (Map.Entry<RegistrationId, TeamId> entry : assignments.entrySet()) {
            visitor.accept(entry.getKey(), entry.getValue());
        }
        return assignments.size();
    }

    /** Validates the invariant that one TeamId cannot be assigned to multiple registrations. */
    void validate() {
        Set<TeamId> teams = new LinkedHashSet<TeamId>();
        for (TeamId teamId : assignments.values()) {
            failIfInvalidArgument(
                    teams.add(teamId),
                    "one TeamId may not be assigned to multiple registrations: %s",
                    teamId);
        }
    }

    /** Replaces live state with a validated transactional assignment copy. */
    void replaceWith(TeamAssignments updatedAssignments) {
        failIfInvalidArgument(updatedAssignments != null, "updatedAssignments must not be null");
        updatedAssignments.validate();
        assignments.clear();
        assignments.putAll(updatedAssignments.assignments);
    }
}
