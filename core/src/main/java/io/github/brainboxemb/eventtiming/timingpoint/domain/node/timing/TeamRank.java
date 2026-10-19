package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import java.time.Duration;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** One ranked team represented by the active stage passage selected for that team. */
public final class TeamRank {
    private final int rank;
    private final StageTimingRecord timingRecord;

    TeamRank(int rank, StageTimingRecord timingRecord) {

        failIfInvalidArgument(rank >= 1, "rank must be positive");
        failIfInvalidArgument(timingRecord != null, "timingRecord must not be null");

        this.rank = rank;
        this.timingRecord = timingRecord;
    }

    /** Returns the competition rank, where equal elapsed times share a rank. */
    public int rank() {

        return rank;
    }

    /** Returns the selected stage passage that represents this team. */
    public StageTimingRecord timingRecord() {

        return timingRecord;
    }

    /** Returns the selected passage elapsed time. */
    public Duration elapsed() {

        return timingRecord.elapsed();
    }
}
