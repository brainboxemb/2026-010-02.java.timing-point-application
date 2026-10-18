package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * Passive committed TimingData history owned by one LogBook Data Lane.
 *
 * <p>LogBookData has no worker or independent synchronization. Its record
 * collection is owned by LogBook and accessed serially through its Data Lane. Only the committed source
 * head is published separately as a volatile value so a DIRECT/CURRENT query
 * can read that scalar without entering the lane.</p>
 */
public final class LogBookData {
    private final NodeId timingNodeId;
    private final List<TimingData> records = new ArrayList<>();

    /*
     * Published after records.add(...). Readers may inspect this scalar directly;
     * they must not use it as permission to access the mutable records list.
     */
    private volatile long headSequence;

    /** Creates empty committed history for one TimingNode identity. */
    public LogBookData(NodeId timingNodeId) {
        failIfInvalidArgument(timingNodeId != null, "timingNodeId must not be null");
        this.timingNodeId = timingNodeId;
    }

    /**
     * Returns the highest committed source sequence, or 0 when empty.
     *
     * <p>This scalar is safe for a direct read. The committed record collection
     * itself remains Data Lane-owned.</p>
     */
    public long headSequence() {
        return headSequence;
    }

    /**
     * Returns the next sequence implied by committed state without consuming it.
     */
    public long nextSequence() {
        long head = headSequence;
        if (head == 0L) {
            return 1L;
        }
        failIfIllegalState(head < TimingData.MAX_SEQUENCE_NUMBER, "TimingData sequence space is exhausted");
        return head + 1L;
    }

    /**
     * Adds one already committed TimingData value.
     */
    public void add(TimingData data) {
        failIfInvalidArgument(data != null, "data must not be null");
        failIfInvalidArgument(
                timingNodeId.equals(data.timingNodeId()),
                "TimingData belongs to a different TimingNode: %s",
                data.timingNodeId());

        long expectedSequence = nextSequence();
        failIfInvalidArgument(
                data.sequenceNumber() == expectedSequence,
                "TimingData sequence must be %s but was %s",
                expectedSequence,
                data.sequenceNumber());
        records.add(data);

        // Publish only after the committed record is present in the lane-owned list.
        headSequence = data.sequenceNumber();
    }

    /** Returns the number of committed TimingData records in lane-owned history. */
    public int size() {
        return records.size();
    }

    /** Returns the newest committed TimingData record. */
    public TimingData latest() {
        failIfIllegalState(!records.isEmpty(), "no committed TimingData is available");
        return records.get(records.size() - 1);
    }

    /**
     * Visits at most {@code limit} committed records starting at the inclusive
     * source sequence.
     *
     * <p>The owner calls this while the Data Lane is current. The visitor is
     * invoked synchronously and must remain short/non-blocking.</p>
     */
    public void visitRange(long fromSequence, int limit, Consumer<TimingData> visitor) {
        failIfInvalidArgument(fromSequence >= 1L, "fromSequence must be >= 1");
        failIfInvalidArgument(limit >= 1, "limit must be >= 1");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

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
        failIfInvalidArgument(limit >= 1, "limit must be >= 1");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        int start = Math.max(0, records.size() - limit);
        for (int index = start; index < records.size(); index++) {
            visitor.accept(records.get(index));
        }
    }
}
