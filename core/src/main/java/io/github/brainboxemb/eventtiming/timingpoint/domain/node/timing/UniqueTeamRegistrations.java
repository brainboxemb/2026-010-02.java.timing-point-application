package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Active registration multiplicity for one TimingNode.
 *
 * <p>This data object deliberately stores RegistrationIds rather than resolved
 * TeamIds. A live TeamAssignment may change later; resolving TeamId only when
 * the unique-team count is requested keeps the count consistent with current
 * EventData/TeamAssignments state.</p>
 *
 * <p>The internal multiplicity is needed because one RegistrationId can have
 * more than one active registration. A single REV therefore removes the
 * RegistrationId only when its last active registration has been revoked.</p>
 */
public final class UniqueTeamRegistrations {
    private final Map<RegistrationId, Integer> activeRegistrations =
            new LinkedHashMap<RegistrationId, Integer>();

    public void registered(RegistrationId registrationId) {
        requireRegistrationId(registrationId);
        Integer current =
                activeRegistrations.get(registrationId);
        activeRegistrations.put(
                registrationId,
                Integer.valueOf(
                        current == null
                                ? 1
                                : current.intValue() + 1));
    }

    public void revoked(RegistrationId registrationId) {
        requireRegistrationId(registrationId);
        Integer current =
                activeRegistrations.get(registrationId);
        if (current == null) {
            return;
        }

        if (current.intValue() <= 1) {
            activeRegistrations.remove(registrationId);
        } else {
            activeRegistrations.put(
                    registrationId,
                    Integer.valueOf(
                            current.intValue() - 1));
        }
    }

    /**
     * Returns the number of currently resolvable unique TeamIds.
     *
     * <p>Unresolved RegistrationIds are intentionally not counted. If a later
     * active assignment resolves one of them, the next call immediately sees
     * that TeamId without rewriting registration history.</p>
     */
    public int uniqueTeamCount(
            Function<RegistrationId, TeamId> teamResolver) {
        if (teamResolver == null) {
            throw new IllegalArgumentException(
                    "teamResolver must not be null");
        }

        Set<TeamId> uniqueTeams =
                new LinkedHashSet<TeamId>();
        for (RegistrationId registrationId
                : activeRegistrations.keySet()) {
            TeamId teamId =
                    teamResolver.apply(registrationId);
            if (teamId != null) {
                uniqueTeams.add(teamId);
            }
        }
        return uniqueTeams.size();
    }

    public int activeRegistrationIdCount() {
        return activeRegistrations.size();
    }

    /** Clears the derived state before rebuilding it from recovered TimingData. */
    public void reset() {
        activeRegistrations.clear();
    }

    private static void requireRegistrationId(
            RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "registrationId must not be null");
        }
    }
}
