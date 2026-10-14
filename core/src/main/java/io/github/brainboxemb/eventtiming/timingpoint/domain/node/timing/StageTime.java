package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.time.Duration;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * One resolved row in the current StageTiming ranking.
 */
public final class StageTime {
    private final int rank;
    private final TeamId teamId;
    private final RegistrationId registrationId;
    private final Duration elapsed;

    StageTime(
            int rank,
            TeamId teamId,
            RegistrationId registrationId,
            Duration elapsed) {
        checkArgument(rank >= 0, "rank must not be negative");
        checkArgument(teamId != null, "teamId must not be null");
        checkArgument(registrationId != null, "registrationId must not be null");
        checkArgument(elapsed != null, "elapsed must not be null");
        this.rank = rank;
        this.teamId = teamId;
        this.registrationId = registrationId;
        this.elapsed = elapsed;
    }

    public int rank() {
        return rank;
    }

    public TeamId teamId() {
        return teamId;
    }

    public RegistrationId registrationId() {
        return registrationId;
    }

    public Duration elapsed() {
        return elapsed;
    }
}
