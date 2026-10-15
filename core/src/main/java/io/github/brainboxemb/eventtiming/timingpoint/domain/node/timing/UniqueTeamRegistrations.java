package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

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

    /** Records one additional active registration for the RegistrationId. */
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

    /** Removes one active registration occurrence when present. */
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
        checkArgument(teamResolver != null, "teamResolver must not be null");

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

    /** Returns the number of RegistrationIds that currently have active registrations. */
    public int activeRegistrationIdCount() {
        return activeRegistrations.size();
    }

    /** Clears derived state before rebuilding it from the initially loaded TimingData history. */
    public void reset() {
        activeRegistrations.clear();
    }

    /** Validates the RegistrationId required by active-registration state changes. */
    private static void requireRegistrationId(
            RegistrationId registrationId) {
        checkArgument(registrationId != null, "registrationId must not be null");
    }
}
