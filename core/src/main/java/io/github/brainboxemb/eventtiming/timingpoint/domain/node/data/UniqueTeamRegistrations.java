package io.github.brainboxemb.eventtiming.timingpoint.domain.node.data;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counts TeamIds with one or more active registrations for one TimingNode.
 *
 * <p>The registration multiplicity is private so duplicate registrations for
 * the same TeamId do not increase the public unique-team count. Revoking one
 * of several active registrations keeps that TeamId counted until its last
 * active registration is revoked.</p>
 */
public final class UniqueTeamRegistrations {
    private final Map<TeamId, Integer> registrations =
            new LinkedHashMap<TeamId, Integer>();

    public void registered(TeamId teamId) {
        requireTeamId(teamId);
        Integer current =
                registrations.get(teamId);
        registrations.put(
                teamId,
                Integer.valueOf(
                        current == null
                                ? 1
                                : current.intValue() + 1));
    }

    public void revoked(TeamId teamId) {
        requireTeamId(teamId);
        Integer current =
                registrations.get(teamId);
        if (current == null) {
            return;
        }
        if (current.intValue() <= 1) {
            registrations.remove(teamId);
        } else {
            registrations.put(
                    teamId,
                    Integer.valueOf(
                            current.intValue() - 1));
        }
    }

    public int count() {
        return registrations.size();
    }

    public boolean contains(TeamId teamId) {
        requireTeamId(teamId);
        return registrations.containsKey(teamId);
    }

    private static void requireTeamId(TeamId teamId) {
        if (teamId == null) {
            throw new IllegalArgumentException(
                    "teamId must not be null");
        }
    }
}
