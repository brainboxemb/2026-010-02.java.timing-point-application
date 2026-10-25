package oss.relay.eventtiming.timingpoint.domain.node.logbook;

import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingdata.TimingRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/** Passive committed scalar timing history owned by one LogBook Data Lane. */
public final class LogBookData {
    private final char timingNodeId;
    private final List<TimingRecord> records = new ArrayList<TimingRecord>();
    private volatile long headSequence;

    public LogBookData(NodeId timingNodeId) {
        failIfInvalidArgument(timingNodeId != null, "timingNodeId must not be null");
        this.timingNodeId = timingNodeId.value().charAt(0);
    }

    public long headSequence() {
        return headSequence;
    }

    public long nextSequence() {
        long head = headSequence;
        if (head == 0L) {
            return 1L;
        }
        failIfIllegalState(head < TimingRecord.MAX_SEQUENCE_NUMBER, "IF-05 sequence space is exhausted");
        return head + 1L;
    }

    public void add(TimingRecord record) {
        failIfInvalidArgument(record != null, "record must not be null");
        failIfInvalidArgument(
                record.nodeId() == timingNodeId,
                "TimingRecord belongs to a different TimingNode: %s",
                record.nodeId());
        long expectedSequence = nextSequence();
        failIfInvalidArgument(
                record.sequenceNumber() == expectedSequence,
                "TimingRecord sequence must be %s but was %s",
                expectedSequence,
                record.sequenceNumber());
        records.add(record);
        headSequence = record.sequenceNumber();
    }

    public int size() {
        return records.size();
    }

    public TimingRecord latest() {
        failIfIllegalState(!records.isEmpty(), "no committed TimingRecord is available");
        return records.get(records.size() - 1);
    }

    public void visitRange(long fromSequence, int limit, Consumer<TimingRecord> visitor) {
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

    public void visitLatest(int limit, Consumer<TimingRecord> visitor) {
        failIfInvalidArgument(limit >= 1, "limit must be >= 1");
        failIfInvalidArgument(visitor != null, "visitor must not be null");
        int start = Math.max(0, records.size() - limit);
        for (int index = start; index < records.size(); index++) {
            visitor.accept(records.get(index));
        }
    }
}
