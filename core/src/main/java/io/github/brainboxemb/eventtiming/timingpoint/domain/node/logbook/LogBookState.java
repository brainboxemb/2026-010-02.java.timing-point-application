package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Passive mutable state behind one LogBook component.
 *
 * <p>This object owns committed in-memory TimingData history and LogBook
 * load-failure state. It has no TimingNode OPEN/CLOSED state, TimingDataFactory,
 * time source, persistence, metrics, queueing, timeout, logging or
 * event-delivery responsibility.</p>
 */
final class LogBookState {
    private final NodeId timingNodeId;
    private final LogBookData logBookData;

    private String loadFailureMessage;

    /** Creates empty passive LogBook state for one TimingNode. */
    LogBookState(NodeId timingNodeId) {

        failIfInvalidArgument(timingNodeId != null, "timingNodeId must not be null");
        this.timingNodeId = timingNodeId;
        this.logBookData = new LogBookData(timingNodeId);
    }

    /** Returns the immutable identity of the owning TimingNode. */
    NodeId timingNodeId() {

        return timingNodeId;
    }

    /** Returns the safely published committed TimingData source head. */
    long timingDataHead() {

        return logBookData.headSequence();
    }

    /** Records failure of persisted TimingData loading. */
    void markTimingDataLoadFailed(String message) {

        failIfInvalidArgument(message != null && !message.trim().isEmpty(), "message must not be blank");
        loadFailureMessage = message.trim();
    }

    /** Rejects normal LogBook access after persisted TimingData loading failed. */
    void ensureOperational() {

        if (loadFailureMessage != null) {
            throw new IllegalStateException(loadFailureMessage);
        }
    }

    /** Returns the next sequence implied by committed history without consuming it. */
    long nextTimingDataSequence() {

        return logBookData.nextSequence();
    }

    /** Adds one already durably committed TimingData value to in-memory history. */
    void addCommittedTimingData(TimingData data) {

        logBookData.add(data);
    }

    /** Returns the committed TimingData count while the LogBook is operational. */
    int timingDataCount() {

        ensureOperational();
        return logBookData.size();
    }

    /** Returns a bounded immutable copy of committed TimingData in source order. */
    List<TimingData> timingDataRange(long fromSequence, int limit) {

        ensureOperational();
        List<TimingData> records = new ArrayList<TimingData>();
        logBookData.visitRange(fromSequence, limit, records::add);
        return Collections.unmodifiableList(records);
    }

    /** Visits a bounded committed range and returns the current total record count. */
    int visitTimingDataRange(long fromSequence, int limit, Consumer<TimingData> visitor) {

        ensureOperational();
        logBookData.visitRange(fromSequence, limit, visitor);
        return logBookData.size();
    }

    /** Visits the newest committed records and returns the current total record count. */
    int visitLatestTimingData(int limit, Consumer<TimingData> visitor) {

        ensureOperational();
        logBookData.visitLatest(limit, visitor);
        return logBookData.size();
    }

    /** Returns the newest committed TimingData record. */
    TimingData latestCommittedTimingData() {

        return logBookData.latest();
    }
}
