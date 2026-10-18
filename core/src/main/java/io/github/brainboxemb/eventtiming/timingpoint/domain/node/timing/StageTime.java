package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.Duration;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** One resolved active registration in the stage passage list. */
public final class StageTime {
    private final LocationId locationId;
    private final TeamId teamId;
    private final RegistrationId registrationId;
    private final TimingTimestamp startedAt;
    private final TimingTimestamp finishedAt;

    StageTime(
            LocationId locationId,
            TeamId teamId,
            RegistrationId registrationId,
            TimingTimestamp startedAt,
            TimingTimestamp finishedAt) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(teamId != null, "teamId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(startedAt != null, "startedAt must not be null");
        failIfInvalidArgument(finishedAt != null, "finishedAt must not be null");
        failIfInvalidArgument(startedAt.isAbsolute(), "startedAt must be absolute");
        failIfInvalidArgument(finishedAt.isAbsolute(), "finishedAt must be absolute");

        this.locationId = locationId;
        this.teamId = teamId;
        this.registrationId = registrationId;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    /** Returns the TimingData location for this passage. */
    public LocationId locationId() {

        return locationId;
    }

    /** Returns the resolved team identity for this passage. */
    public TeamId teamId() {

        return teamId;
    }

    /** Returns the registration that produced this passage. */
    public RegistrationId registrationId() {

        return registrationId;
    }

    /** Returns the resolved absolute stage start. */
    public TimingTimestamp startedAt() {

        return startedAt;
    }

    /** Returns the registration's absolute effective passage time. */
    public TimingTimestamp finishedAt() {

        return finishedAt;
    }

    /** Returns the elapsed time between stage start and this passage. */
    public Duration elapsed() {

        return Duration.between(startedAt.instant(), finishedAt.instant());
    }
}
