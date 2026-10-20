package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;

import java.util.List;
import java.util.function.Consumer;

/** Standard read operations owned by the LogBook/Data Lane component. */
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

    private LogBookQueries() {
    }

    public static LogBookQuery<Integer> timingDataCount() {
        return TIMING_DATA_COUNT;
    }

    public static LogBookQuery<Long> timingDataHead() {
        return TIMING_DATA_HEAD;
    }

    /** Returns a bounded immutable scalar-record range. */
    public static LogBookQuery<List<TimingRecord>> timingRecordRange(
            long fromSequence,
            int limit) {
        return new LogBookQuery<>(
                "timingRecordRange",
                state -> state.timingRecordRange(fromSequence, limit));
    }

    /** Visits a bounded scalar-record range on the Data Lane. */
    public static LogBookQuery<Integer> visitTimingRecordRange(
            long fromSequence,
            int limit,
            Consumer<TimingRecord> visitor) {
        return new LogBookQuery<>(
                "visitTimingRecordRange",
                state -> state.visitTimingRecordRange(fromSequence, limit, visitor));
    }

    /** Visits the newest scalar records on the Data Lane. */
    public static LogBookQuery<Integer> visitLatestTimingRecords(
            int limit,
            Consumer<TimingRecord> visitor) {
        return new LogBookQuery<>(
                "visitLatestTimingRecords",
                state -> state.visitLatestTimingRecords(limit, visitor));
    }
}
