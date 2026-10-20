package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Calculates the provisional competition rank of one registration passage.
 *
 * <p>Every valid registration at the same WP is considered independently, so repeated
 * passages by one team remain separate competitors. Registrations whose current TeamId
 * or stage start cannot be resolved do not participate.</p>
 *
 * <p>The calculation scans ActiveRegistrations once. Rank is one plus the number of
 * eligible passages with a shorter elapsed time. Equal elapsed times therefore share
 * the same competition rank without constructing or sorting a complete ranking list.</p>
 */
public final class RegistrationRanking {

    private RegistrationRanking() {
    }

    /** Returns the current rank for one retained registration, or null when it is not rankable. */
    public static RegistrationRank rank(
            ActiveRegistrations registrations,
            RaceDataState raceData,
            ActiveRegistrations.Registration registration) {

        failIfInvalidArgument(registrations != null, "registrations must not be null");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        failIfInvalidArgument(registration != null, "registration must not be null");

        if (!registration.isValid()) {
            return null;
        }

        TeamId teamId = raceData.teamIdFor(registration.registrationId());
        Long startMillis = teamId == null ? null : raceData.stageStartTimeMillisFor(teamId);
        if (startMillis == null) {
            return null;
        }

        long elapsedMillis = registration.effectiveTimeMillis() - startMillis.longValue();
        int rank = 1;
        boolean retained = false;

        for (ActiveRegistrations.Registration other : registrations.values()) {
            if (other == registration) {
                retained = true;
            } else if (other.isValid() && other.locationIdValue() == registration.locationIdValue()) {
                TeamId otherTeamId = raceData.teamIdFor(other.registrationId());
                Long otherStartMillis =
                        otherTeamId == null ? null : raceData.stageStartTimeMillisFor(otherTeamId);
                if (otherStartMillis != null
                        && other.effectiveTimeMillis() - otherStartMillis.longValue() < elapsedMillis) {
                    rank++;
                }
            }
        }

        failIfInvalidArgument(retained, "registration must belong to ActiveRegistrations");
        return new RegistrationRank(
                rank,
                StageTiming.resolve(registration, teamId, startMillis.longValue()));
    }
}
