package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns TIMING_DATA source-head and replay state for one NodeUpstream.
 *
 * <p>The LogBook is the TIMING_DATA source of truth. One REQ captures the
 * current source head once; records committed afterwards are intentionally
 * excluded. Ordered batch reads are asynchronous, while NodeUpstream remains
 * responsible for encoding and sending the semantic messages produced here.</p>
 */
final class TimingDataSync {
    private static final int MAX_RECORDS_PER_BATCH = 256;

    private final NodeId nodeId;
    private final BooleanSupplier available;
    private final Consumer<UpstreamData> sender;
    private final LogBookAccessor logBook;

    private boolean replayActive;
    private long nextSequence;
    private long replayHead;

    TimingDataSync(
            NodeId nodeId,
            BooleanSupplier available,
            Consumer<UpstreamData> sender,
            LogBookAccessor logBook) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(sender != null, "sender must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        this.nodeId = nodeId;
        this.available = available;
        this.sender = sender;
        this.logBook = logBook;
    }

    /** Sends the current TIMING_DATA source head when transport is available. */
    void sendSourceHead() {
        if (available.getAsBoolean()) {
            sender.accept(
                    DefaultUpstreamData.sync(
                            nodeId,
                            UpstreamData.Stream.TIMING_DATA,
                            headSequence()));
        }
    }

    /** Returns the current committed TIMING_DATA source head; empty means zero. */
    long headSequence() {
        return logBook.process(
                LogBookQueries.timingDataHead(),
                ReadConsistency.CURRENT);
    }

    /** Starts one replay at the inclusive sequence requested by the peer. */
    void startReplay(
            UpstreamData.Req req) {
        failIfInvalidArgument(req != null, "req must not be null");
        if (req.stream() != UpstreamData.Stream.TIMING_DATA) {
            throw new UnsupportedOperationException(
                    "Inbound REQ supports TIMING_DATA only");
        }

        long fromSequence = req.fromSequence();
        failIfInvalidArgument(
                fromSequence >= 1L
                        && fromSequence <= TimingRecord.MAX_SEQUENCE_NUMBER,
                "REQ fromSequence is outside the supported range");

        long capturedHead = headSequence();
        if (fromSequence > capturedHead) {
            replayActive = false;
        } else {
            nextSequence = fromSequence;
            replayHead = capturedHead;
            replayActive = true;
        }
    }

    /** Reports whether the captured replay still has records to send. */
    boolean replayPending() {
        return replayActive;
    }

    /** Cancels the current replay without changing LogBook state. */
    void cancelReplay() {
        replayActive = false;
    }

    /** Submits the next ordered LogBook batch read without blocking NodeUpstream. */
    CompletableFuture<List<TimingRecord>> submitNextBatch() {
        failIfIllegalState(replayActive,
                "No TIMING_DATA synchronization is active");

        int limit =
                (int) Math.min(
                        (long) MAX_RECORDS_PER_BATCH,
                        replayHead - nextSequence + 1L);

        return logBook.submit(
                LogBookQueries.timingRecordRange(
                        nextSequence,
                        limit));
    }

    /** Validates and sends one completed ordered LogBook batch. */
    void sendNextBatch(
            List<TimingRecord> records) {
        failIfInvalidArgument(records != null, "records must not be null");

        if (records.isEmpty()) {
            replayActive = false;
        }
        failIfIllegalState(!records.isEmpty(),
                "LogBook head %s advertised record %s but synchronization returned no data",
                replayHead,
                nextSequence);

        TimingRecord first = records.get(0);
        if (first.sequenceNumber() != nextSequence) {
            replayActive = false;
        }
        failIfIllegalState(first.sequenceNumber() == nextSequence,
                "TimingData synchronization expected sequence %s but got %s",
                nextSequence,
                first.sequenceNumber());

        TimingRecord last = records.get(records.size() - 1);
        if (last.sequenceNumber() > replayHead) {
            replayActive = false;
        }
        failIfIllegalState(last.sequenceNumber() <= replayHead,
                "TimingData synchronization crossed captured head %s",
                replayHead);

        sender.accept(
                DefaultUpstreamData.timingData(
                        nodeId,
                        records));

        if (last.sequenceNumber() >= replayHead) {
            replayActive = false;
        } else {
            nextSequence = last.sequenceNumber() + 1L;
        }
    }
}
