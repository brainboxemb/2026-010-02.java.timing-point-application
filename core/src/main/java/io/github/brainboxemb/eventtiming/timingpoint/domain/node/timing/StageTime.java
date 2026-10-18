package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.Duration;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** One resolved row in the current StageTiming ranking. */
public final class StageTime {
    private final int rank;
    private final TeamId teamId;
    private final RegistrationId registrationId;
    private final TimingTimestamp startedAt;
    private final TimingTimestamp finishedAt;

    /** Creates one resolved ranking row from its start and finish timestamps. */
    StageTime(
            int rank,
            TeamId teamId,
            RegistrationId registrationId,
            TimingTimestamp startedAt,
            TimingTimestamp finishedAt) {

        failIfInvalidArgument(rank >= 0, "rank must not be negative");
        failIfInvalidArgument(teamId != null, "teamId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(startedAt != null, "startedAt must not be null");
        failIfInvalidArgument(finishedAt != null, "finishedAt must not be null");
        failIfInvalidArgument(startedAt.isAbsolute(), "startedAt must be absolute");
        failIfInvalidArgument(finishedAt.isAbsolute(), "finishedAt must be absolute");

        this.rank = rank;
        this.teamId = teamId;
        this.registrationId = registrationId;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
    }

    /** Returns the competition rank, where equal elapsed times share a rank. */
    public int rank() {

        return rank;
    }

    /** Returns the resolved team identity for this ranking row. */
    public TeamId teamId() {

        return teamId;
    }

    /** Returns the registration that produced this stage finish. */
    public RegistrationId registrationId() {

        return registrationId;
    }

    /** Returns the resolved absolute stage start. */
    public TimingTimestamp startedAt() {

        return startedAt;
    }

    /** Returns the registration's absolute effective finish time. */
    public TimingTimestamp finishedAt() {

        return finishedAt;
    }

    /** Returns the elapsed time between stage start and finish. */
    public Duration elapsed() {

        return Duration.between(startedAt.instant(), finishedAt.instant());
    }
}
