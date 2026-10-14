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

    public TeamAssignments() {
        this.assignments =
                new LinkedHashMap<RegistrationId, TeamId>();
    }

    private TeamAssignments(
            Map<RegistrationId, TeamId> assignments) {
        this.assignments =
                new LinkedHashMap<RegistrationId, TeamId>(assignments);
    }

    public TeamAssignments copy() {
        return new TeamAssignments(assignments);
    }

    public TeamId teamIdFor(
            RegistrationId registrationId) {
        checkArgument(registrationId != null, "registrationId must not be null");
        return assignments.get(registrationId);
    }

    public void assign(
            RegistrationId registrationId,
            TeamId teamId) {
        checkArgument(registrationId != null, "registrationId must not be null");
        checkArgument(teamId != null, "teamId must not be null");
        assignments.put(
                registrationId,
                teamId);
    }

    public void remove(
            RegistrationId registrationId) {
        checkArgument(registrationId != null, "registrationId must not be null");
        assignments.remove(registrationId);
    }

    public void validate() {
        Set<TeamId> teams =
                new LinkedHashSet<TeamId>();
        for (TeamId teamId : assignments.values()) {
            if (!teams.add(teamId)) {
                throw new IllegalArgumentException(
                        "one TeamId may not be assigned to multiple registrations: "
                                + teamId);
            }
        }
    }

    public void replaceWith(
            TeamAssignments updatedAssignments) {
        checkArgument(updatedAssignments != null, "updatedAssignments must not be null");
        updatedAssignments.validate();
        assignments.clear();
        assignments.putAll(
                updatedAssignments.assignments);
    }
}
