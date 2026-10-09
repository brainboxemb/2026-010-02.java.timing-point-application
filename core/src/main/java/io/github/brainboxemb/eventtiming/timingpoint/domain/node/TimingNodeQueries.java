package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;

import java.util.function.Consumer;

/**
 * Standard read operations supported by TimingNode.
 *
 * <p>Queries are represented as typed values so a new read does not require
 * another forwarding method on the TimingNode component boundary. Commands use
 * the matching TimingNodeCommand/TimingNodeCommands path.</p>
 */
public final class TimingNodeQueries {
    private static final TimingNodeQuery<Status> STATUS =
            new TimingNodeQuery<>("status", TimingNodeLogic::status);
    private static final TimingNodeQuery<Integer> TIMING_DATA_COUNT =
            new TimingNodeQuery<>(
                    "timingDataCount",
                    TimingNodeLogic::timingDataCount);

    private TimingNodeQueries() {
    }

    public static TimingNodeQuery<Status> status() {
        return STATUS;
    }

    public static TimingNodeQuery<Integer> timingDataCount() {
        return TIMING_DATA_COUNT;
    }

    /**
     * Visits a bounded LogBook range on the TimingNode serial lane.
     *
     * <p>The visitor must be short/non-blocking and must not re-enter the same
     * TimingNode. The returned value is the total committed LogBook count from
     * the same ordered read.</p>
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
