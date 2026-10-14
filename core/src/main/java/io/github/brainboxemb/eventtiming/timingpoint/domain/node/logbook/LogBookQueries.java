package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import java.util.function.Consumer;

/**
 * Standard read operations owned by the LogBook/Data Lane component.
 *
 * <p>Status and the published LogBook source head support CURRENT direct reads
 * as well as ORDERED reads. Mutable LogBook record/range access remains ORDERED.</p>
 */
public final class LogBookQueries {
    private static final LogBookQuery<Status> STATUS =
            new LogBookQuery<>(
                    "status",
                    LogBookState::status,
                    LogBookProcessor::publishedStatus);
    private static final LogBookQuery<Integer> TIMING_DATA_COUNT =
            new LogBookQuery<>(
                    "timingDataCount",
                    LogBookState::timingDataCount);
    private static final LogBookQuery<Long> TIMING_DATA_HEAD =
            new LogBookQuery<>(
                    "timingDataHead",
                    LogBookState::timingDataHead,
                    LogBookProcessor::currentTimingDataHead);

    private LogBookQueries() {
    }

    public static LogBookQuery<Status> status() {
        return STATUS;
    }

    public static LogBookQuery<Integer> timingDataCount() {
        return TIMING_DATA_COUNT;
    }

    /**
     * Returns the highest committed TimingData sequence, or 0 when the LogBook is empty.
     *
     * <p>CURRENT reads use the LogBook's published scalar head directly. ORDERED
     * reads enter the Data Lane and therefore observe work accepted earlier.</p>
     */
    public static LogBookQuery<Long> timingDataHead() {
        return TIMING_DATA_HEAD;
    }


    /**
     * Visits at most {@code limit} LogBook records on the Data Lane.
     *
     * <p>The visitor must be short/non-blocking and must not make a result-bearing
     * ORDERED call back into the same TimingNode. CURRENT status reads are safe.</p>
     */
    public static LogBookQuery<Integer> visitTimingDataRange(
            long fromSequence,
            int limit,
            Consumer<TimingData> visitor) {
        return new LogBookQuery<>(
                "visitTimingDataRange",
                logic -> logic.visitTimingDataRange(
                        fromSequence,
                        limit,
                        visitor));
    }

    /**
     * Visits at most {@code limit} newest LogBook records on the Data Lane.
     */
    public static LogBookQuery<Integer> visitLatestTimingData(
            int limit,
            Consumer<TimingData> visitor) {
        return new LogBookQuery<>(
                "visitLatestTimingData",
                logic -> logic.visitLatestTimingData(limit, visitor));
    }
}
