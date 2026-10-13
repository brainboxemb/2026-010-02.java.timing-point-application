package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;

import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

/**
 * Standard read operations supported by TimingNode.
 *
 * <p>Status and the published LogBook source head support CURRENT direct reads
 * as well as ORDERED reads. Mutable LogBook record/range access remains ORDERED.</p>
 */
public final class TimingNodeQueries {
    private static final TimingNodeQuery<Status> STATUS =
            new TimingNodeQuery<>(
                    "status",
                    TimingNodeLogic::status,
                    TimingNode::publishedStatus);
    private static final TimingNodeQuery<Integer> TIMING_DATA_COUNT =
            new TimingNodeQuery<>(
                    "timingDataCount",
                    TimingNodeLogic::timingDataCount);
    private static final TimingNodeQuery<Long> TIMING_DATA_HEAD =
            new TimingNodeQuery<>(
                    "timingDataHead",
                    TimingNodeLogic::timingDataHead,
                    TimingNode::currentTimingDataHead);

    private TimingNodeQueries() {
    }

    public static TimingNodeQuery<Status> status() {
        return STATUS;
    }

    public static TimingNodeQuery<Integer> timingDataCount() {
        return TIMING_DATA_COUNT;
    }

    /**
     * Returns the highest committed TimingData sequence, or 0 when the LogBook is empty.
     *
     * <p>CURRENT reads use the LogBook's published scalar head directly. ORDERED
     * reads enter the TimingNode lane and therefore observe work accepted earlier.</p>
     */
    public static TimingNodeQuery<Long> timingDataHead() {
        return TIMING_DATA_HEAD;
    }


    /**
     * Returns the current resolved stage-time ranking for this TimingNode.
     */
    public static TimingNodeQuery<List<StageTime>> stageTiming(
            ZoneId raceTimeZone) {
        if (raceTimeZone == null) {
            throw new IllegalArgumentException(
                    "raceTimeZone must not be null");
        }
        return new TimingNodeQuery<>(
                "stageTiming",
                logic -> logic.stageTiming(
                        raceTimeZone));
    }

    /**
     * Visits a bounded LogBook range on the TimingNode serial lane.
     *
     * <p>The visitor must be short/non-blocking and must not make a result-bearing
     * ORDERED call back into the same TimingNode. CURRENT status reads are safe.</p>
     */
    public static TimingNodeQuery<Integer> visitTimingDataRange(
            long fromSequence,
            int limit,
            Consumer<TimingData> visitor) {
        return new TimingNodeQuery<>(
                "visitTimingDataRange",
                logic -> logic.visitTimingDataRange(
                        fromSequence,
                        limit,
                        visitor));
    }

    /**
     * Visits a bounded newest LogBook range on the TimingNode serial lane.
     */
    public static TimingNodeQuery<Integer> visitLatestTimingData(
            int limit,
            Consumer<TimingData> visitor) {
        return new TimingNodeQuery<>(
                "visitLatestTimingData",
                logic -> logic.visitLatestTimingData(limit, visitor));
    }
}
