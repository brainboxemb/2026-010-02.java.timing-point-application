package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.Instant;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Calculates one stage timing record from absolute internal timing state.
 *
 * <p>RACE_DATA ingress has already normalized the stage start to UTC. This calculation
 * therefore owns no civil-date or time-zone interpretation; it only combines the
 * requested registration with its resolved absolute start.</p>
 */
public final class StageTiming {

    private StageTiming() {
    }

    /** Calculates the passage time for an already resolved team and absolute stage start. */
    public static StageTimingRecord resolve(
            ActiveRegistrations.Registration registration,
            TeamId teamId,
            long startTimeMillis) {

        failIfInvalidArgument(registration != null, "registration must not be null");
        failIfInvalidArgument(teamId != null, "teamId must not be null");

        TimingTimestamp startTime =
                new TimingTimestamp(Instant.ofEpochMilli(startTimeMillis));
        return new StageTimingRecord(
                registration.sequenceNumber(),
                registration.locationId(),
                teamId,
                registration.registrationId(),
                startTime,
                registration.effectiveTime());
    }
}
