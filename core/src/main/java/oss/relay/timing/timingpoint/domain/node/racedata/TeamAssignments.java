package oss.relay.timing.timingpoint.domain.node.racedata;

import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Package-private live RegistrationId-to-TeamId assignment state owned by RaceDataState. */
final class TeamAssignments {
    private final Map<RegistrationId, TeamId> assignments =
            new LinkedHashMap<RegistrationId, TeamId>();

    TeamId teamIdFor(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return assignments.get(registrationId);
    }

    RegistrationId registrationIdFor(TeamId teamId) {
        failIfInvalidArgument(teamId != null, "teamId must not be null");
        for (Map.Entry<RegistrationId, TeamId> entry : assignments.entrySet()) {
            if (teamId.equals(entry.getValue())) return entry.getKey();
        }
        return null;
    }

    void assign(RegistrationId registrationId, TeamId teamId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(teamId != null, "teamId must not be null");
        assignments.put(registrationId, teamId);
    }

    void remove(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        assignments.remove(registrationId);
    }

    int visit(BiConsumer<RegistrationId, TeamId> visitor) {
        failIfInvalidArgument(visitor != null, "visitor must not be null");
        for (Map.Entry<RegistrationId, TeamId> entry : assignments.entrySet()) {
            visitor.accept(entry.getKey(), entry.getValue());
        }
        return assignments.size();
    }
}
