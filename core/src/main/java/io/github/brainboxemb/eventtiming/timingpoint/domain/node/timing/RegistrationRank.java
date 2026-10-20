package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import java.time.Duration;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** One active registration passage with its current provisional competition rank. */
public final class RegistrationRank {
    private final int rank;
    private final StageTimingRecord timingRecord;

    RegistrationRank(int rank, StageTimingRecord timingRecord) {

        failIfInvalidArgument(rank >= 1, "rank must be positive");
        failIfInvalidArgument(timingRecord != null, "timingRecord must not be null");

        this.rank = rank;
        this.timingRecord = timingRecord;
    }

    /** Returns the competition rank, where equal elapsed times share a rank. */
    public int rank() {

        return rank;
    }

    /** Returns the stage passage being ranked. */
    public StageTimingRecord timingRecord() {

        return timingRecord;
    }

    /** Returns the ranked passage elapsed time. */
    public Duration elapsed() {

        return timingRecord.elapsed();
    }
}
