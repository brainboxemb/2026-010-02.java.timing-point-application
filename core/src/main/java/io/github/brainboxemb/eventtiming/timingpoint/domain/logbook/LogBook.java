package io.github.brainboxemb.eventtiming.timingpoint.domain.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Passive committed TimingData history owned by one TimingNode serial lane.
 *
 * <p>The LogBook has no worker or independent synchronization. Its owner decides
 * when mutations and reads occur.</p>
 */
public final class LogBook {
    private final NodeId timingNodeId;
    private final List<TimingData> records = new ArrayList<>();

    public LogBook(NodeId timingNodeId) {
        if (timingNodeId == null) {
            throw new IllegalArgumentException("timingNodeId must not be null");
        }
        this.timingNodeId = timingNodeId;
    }

    /** Returns the highest committed source sequence, or 0 when empty. */
    public long headSequence() {
        return records.isEmpty()
                ? 0L
                : records.get(records.size() - 1).sequenceNumber();
    }

    /**
     * Returns the next sequence implied by committed state without consuming it.
     */
    public long nextSequence() {
        if (records.isEmpty()) {
            return 1L;
        }
        long last = records.get(records.size() - 1).sequenceNumber();
        if (last >= TimingData.MAX_SEQUENCE_NUMBER) {
            throw new IllegalStateException("TimingData sequence space is exhausted");
        }
        return last + 1L;
    }

    /**
     * Adds one already committed TimingData value.
     */
    public void add(TimingData data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (!timingNodeId.equals(data.timingNodeId())) {
            throw new IllegalArgumentException(
                    "TimingData belongs to a different TimingNode: " + data.timingNodeId());
        }

        long expectedSequence = nextSequence();
        if (data.sequenceNumber() != expectedSequence) {
            throw new IllegalArgumentException(
                    "TimingData sequence must be " + expectedSequence
                            + " but was " + data.sequenceNumber());
        }
        records.add(data);
    }

    public int size() {
        return records.size();
    }

    /**
     * Visits at most {@code limit} committed records starting at the inclusive
     * source sequence.
     *
     * <p>The owner must call this only while it owns the LogBook. The visitor is
     * invoked synchronously and must remain short/non-blocking.</p>
     */
    public void visitRange(
            long fromSequence,
            int limit,
            Consumer<TimingData> visitor) {
        if (fromSequence < 1L) {
            throw new IllegalArgumentException("fromSequence must be >= 1");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        if (visitor == null) {
            throw new IllegalArgumentException("visitor must not be null");
        }

        long startLong = fromSequence - 1L;
        if (startLong >= records.size()) {
            return;
        }

        int start = (int) startLong;
        int end = (int) Math.min((long) records.size(), startLong + limit);
        for (int index = start; index < end; index++) {
            visitor.accept(records.get(index));
        }
    }

    /**
     * Visits at most {@code limit} newest committed records in source order.
     */
    public void visitLatest(int limit, Consumer<TimingData> visitor) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        if (visitor == null) {
            throw new IllegalArgumentException("visitor must not be null");
        }

        int start = Math.max(0, records.size() - limit);
        for (int index = start; index < records.size(); index++) {
            visitor.accept(records.get(index));
        }
    }

}
