package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Ranks every valid registration of one WP by elapsed time, including repeated teams.
 *
 * <p>For the intended few hundred entries, this stateless calculation avoids a
 * second mutable registration index and uses the current team assignments and start times.</p>
 */
public final class RegistrationRanking {
    private static final Comparator<StageTimingRecord> FASTEST_FIRST =
            Comparator.comparing(StageTimingRecord::elapsed)
                    .thenComparingLong(StageTimingRecord::sequenceNumber);

    private RegistrationRanking() {
    }

    /** Visits at most limit individual registrations, with competition ranks for equal elapsed times. */
    public static int visit(
            ActiveRegistrations registrations,
            RaceDataState raceData,
            LocationId locationId,
            ZoneId raceTimeZone,
            int limit,
            Consumer<RegistrationRank> visitor) {

        failIfInvalidArgument(registrations != null, "registrations must not be null");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        if (limit == 0) {
            return 0;
        }

        List<StageTimingRecord> times = new ArrayList<>();
        for (ActiveRegistrations.Registration registration : registrations.values()) {
            if (registration.isValid() && locationId.equals(registration.locationId())) {
                TeamId teamId = raceData.teamIdFor(registration.registrationId());
                TimingTimestamp start = teamId == null ? null : raceData.stageStartTimeFor(teamId);
                if (start != null) {
                    times.add(StageTiming.resolve(registration.data(), teamId, start, raceTimeZone));
                }
            }
        }
        times.sort(FASTEST_FIRST);

        Duration previousElapsed = null;
        int rank = 0;
        int visited = Math.min(limit, times.size());
        for (int index = 0; index < visited; index++) {
            StageTimingRecord timingRecord = times.get(index);
            Duration elapsed = timingRecord.elapsed();
            if (!elapsed.equals(previousElapsed)) {
                rank = index + 1;
                previousElapsed = elapsed;
            }
            visitor.accept(new RegistrationRank(rank, timingRecord));
        }
        return visited;
    }
}
