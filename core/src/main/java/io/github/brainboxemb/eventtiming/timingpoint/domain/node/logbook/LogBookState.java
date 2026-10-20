package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Passive mutable state behind one LogBook component. */
final class LogBookState {
    private final NodeId timingNodeId;
    private final LogBookData logBookData;
    private String loadFailureMessage;

    LogBookState(NodeId timingNodeId) {
        failIfInvalidArgument(timingNodeId != null, "timingNodeId must not be null");
        this.timingNodeId = timingNodeId;
        this.logBookData = new LogBookData(timingNodeId);
    }

    NodeId timingNodeId() {
        return timingNodeId;
    }

    long timingDataHead() {
        return logBookData.headSequence();
    }

    void markTimingDataLoadFailed(String message) {
        failIfInvalidArgument(
                message != null && !message.trim().isEmpty(),
                "message must not be blank");
        loadFailureMessage = message.trim();
    }

    void ensureOperational() {
        if (loadFailureMessage != null) {
            throw new IllegalStateException(loadFailureMessage);
        }
    }

    long nextTimingDataSequence() {
        return logBookData.nextSequence();
    }

    void addCommittedRecord(TimingRecord record) {
        logBookData.add(record);
    }

    int timingDataCount() {
        ensureOperational();
        return logBookData.size();
    }

    List<TimingRecord> timingRecordRange(long fromSequence, int limit) {
        ensureOperational();
        List<TimingRecord> records = new ArrayList<TimingRecord>();
        logBookData.visitRange(fromSequence, limit, records::add);
        return Collections.unmodifiableList(records);
    }

    int visitTimingRecordRange(
            long fromSequence,
            int limit,
            Consumer<TimingRecord> visitor) {
        ensureOperational();
        logBookData.visitRange(fromSequence, limit, visitor);
        return logBookData.size();
    }

    int visitLatestTimingRecords(
            int limit,
            Consumer<TimingRecord> visitor) {
        ensureOperational();
        logBookData.visitLatest(limit, visitor);
        return logBookData.size();
    }

    TimingRecord latestCommittedRecord() {
        return logBookData.latest();
    }
}
