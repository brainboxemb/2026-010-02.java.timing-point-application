package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Active RegistrationId-to-TeamId assignments for one TimingNode.
 *
 * <p>The storage representation is private. Callers work only with semantic
 * assignment operations.</p>
 */
public final class TeamAssignments {
    private final Map<RegistrationId, TeamId> assignments;

    /** Creates empty live assignment state. */
    public TeamAssignments() {
        this.assignments = new LinkedHashMap<RegistrationId, TeamId>();
    }

    /** Creates an independent mutable copy from existing assignment values. */
    private TeamAssignments(Map<RegistrationId, TeamId> assignments) {
        this.assignments = new LinkedHashMap<RegistrationId, TeamId>(assignments);
    }

    /** Returns an independent mutable copy for transactional validation. */
    public TeamAssignments copy() {
        return new TeamAssignments(assignments);
    }

    /** Returns the live TeamId assignment for one RegistrationId, or null when absent. */
    public TeamId teamIdFor(RegistrationId registrationId) {
        checkArgument(registrationId != null, "registrationId must not be null");
        return assignments.get(registrationId);
    }

    /** Sets the live TeamId assignment for one RegistrationId. */
    public void assign(RegistrationId registrationId, TeamId teamId) {
        checkArgument(registrationId != null, "registrationId must not be null");
        checkArgument(teamId != null, "teamId must not be null");
        assignments.put(registrationId, teamId);
    }

    /** Removes any live assignment for the RegistrationId. */
    public void remove(RegistrationId registrationId) {
        checkArgument(registrationId != null, "registrationId must not be null");
        assignments.remove(registrationId);
    }

    /** Validates the invariant that one TeamId cannot be assigned to multiple registrations. */
    public void validate() {
        Set<TeamId> teams = new LinkedHashSet<TeamId>();
        for (TeamId teamId : assignments.values()) {
            checkArgument(
                    teams.add(teamId),
                    "one TeamId may not be assigned to multiple registrations: %s",
                    teamId);
        }
    }

    /** Replaces live state with a validated transactional assignment copy. */
    public void replaceWith(TeamAssignments updatedAssignments) {
        checkArgument(updatedAssignments != null, "updatedAssignments must not be null");
        updatedAssignments.validate();
        assignments.clear();
        assignments.putAll(updatedAssignments.assignments);
    }
}
