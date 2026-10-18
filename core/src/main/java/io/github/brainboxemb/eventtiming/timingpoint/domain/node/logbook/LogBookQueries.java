package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import java.util.List;
import java.util.function.Consumer;

/**
 * Standard read operations owned by the LogBook/Data Lane component.
 *
 * <p>The published LogBook source head supports CURRENT direct reads as well as
 * ORDERED reads. Mutable record/range access remains ORDERED.</p>
 */
public final class LogBookQueries {
    private static final LogBookQuery<Integer> TIMING_DATA_COUNT =
            new LogBookQuery<>(
                    "timingDataCount",
                    LogBookState::timingDataCount);
    private static final LogBookQuery<Long> TIMING_DATA_HEAD =
            new LogBookQuery<>(
                    "timingDataHead",
                    LogBookState::timingDataHead,
                    LogBook::currentTimingDataHead);

    /** Prevents construction of this stateless query factory. */
    private LogBookQueries() {
    }


    /** Returns the number of committed TimingData records. */
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
     * Returns at most {@code limit} committed records starting at the inclusive
     * source sequence.
     *
     * <p>The result is a bounded immutable copy produced on the Data Lane.</p>
     */
    public static LogBookQuery<List<TimingData>> timingDataRange(
            long fromSequence,
            int limit) {
        return new LogBookQuery<>(
                "timingDataRange",
                logBookState -> logBookState.timingDataRange(
                        fromSequence,
                        limit));
    }

    /**
     * Visits at most {@code limit} LogBook records on the Data Lane.
     *
     * <p>The visitor must be short/non-blocking and must not make a result-bearing
     * ORDERED call back into the same TimingNode. CURRENT recovery reads are safe.</p>
     */
    public static LogBookQuery<Integer> visitTimingDataRange(
            long fromSequence,
            int limit,
            Consumer<TimingData> visitor) {
        return new LogBookQuery<>(
                "visitTimingDataRange",
                logBookState -> logBookState.visitTimingDataRange(
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
                logBookState -> logBookState.visitLatestTimingData(limit, visitor));
    }
}
