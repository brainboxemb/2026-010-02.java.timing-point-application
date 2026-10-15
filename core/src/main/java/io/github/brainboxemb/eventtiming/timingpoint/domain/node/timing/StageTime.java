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

    /** Creates one resolved ranking row. */
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

    /** Returns the competition rank, where equal elapsed times share a rank. */
    public int rank() {
        return rank;
    }

    /** Returns the resolved team identity for this ranking row. */
    public TeamId teamId() {
        return teamId;
    }

    /** Returns the registration that produced this elapsed-time row. */
    public RegistrationId registrationId() {
        return registrationId;
    }

    /** Returns elapsed time between the resolved stage start and registration. */
    public Duration elapsed() {
        return elapsed;
    }
}
