package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Passive TimingNode-local reserve RegistrationId to TeamId state. */
final class ReserveTagMap {
    private final Map<RegistrationId, TeamId> assignments =
            new LinkedHashMap<RegistrationId, TeamId>();

    TeamId teamIdFor(RegistrationId registrationId) {
        return assignments.get(registrationId);
    }

    Map<RegistrationId, TeamId> candidateAfter(
            List<UpstreamData.ReserveTagMapChange> changes) {
        Map<RegistrationId, TeamId> candidate =
                new LinkedHashMap<RegistrationId, TeamId>(assignments);

        for (UpstreamData.ReserveTagMapChange change : changes) {
            switch (change.operation()) {
                case SET:
                    candidate.put(
                            change.registrationId(),
                            change.teamId());
                    break;
                case DEL:
                    candidate.remove(change.registrationId());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unsupported reserveTagMap operation "
                                    + change.operation());
            }
        }

        Set<TeamId> teams = new LinkedHashSet<TeamId>();
        for (TeamId teamId : candidate.values()) {
            if (!teams.add(teamId)) {
                throw new IllegalArgumentException(
                        "one TeamId may not be assigned to multiple reserve registrations: "
                                + teamId);
            }
        }
        return candidate;
    }

    void replaceWith(Map<RegistrationId, TeamId> candidate) {
        assignments.clear();
        assignments.putAll(candidate);
    }
}
