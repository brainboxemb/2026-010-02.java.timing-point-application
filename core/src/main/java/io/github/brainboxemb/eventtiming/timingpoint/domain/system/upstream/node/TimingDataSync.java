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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns TIMING_DATA position exchange, replay and live source-cursor state for one NodeUpstream.
 *
 * <p>The LogBook remains authoritative. A fresh source-head notification disables live
 * forwarding. The peer's following TIMING_DATA_REQ captures one replay head; replay never
 * crosses that head. Only after the captured replay completes does the live cursor continue
 * from the next LogBook sequence.</p>
 */
final class TimingDataSync {
    private static final Logger LOG = LoggerFactory.getLogger(TimingDataSync.class);
    private static final int MAX_RECORDS_PER_BATCH = UpstreamData.MAX_TIMING_DATA_RECORDS;

    private final NodeId nodeId;
    private final BooleanSupplier available;
    private final Consumer<UpstreamData.Message> sender;
    private final LogBookAccessor logBook;

    private boolean replayActive;
    private long nextReplaySequence;
    private long replayHead;

    private boolean liveActive;
    private long nextLiveSequence;
    private long centralProcessedSequence;

    TimingDataSync(
            NodeId nodeId,
            BooleanSupplier available,
            Consumer<UpstreamData.Message> sender,
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

    /** Starts a fresh handoff and notifies the current TIMING_DATA source head. */
    synchronized void sendSourceHeadNotification() {
        replayActive = false;
        liveActive = false;

        if (available.getAsBoolean()) {
            sender.accept(
                    DefaultUpstreamData.createSequenceNotification(
                            headSequence()));
        }
    }

    /** Handles one peer TIMING_DATA sequence request, answer or notification. */
    synchronized void handleSequence(UpstreamData.Sequence sequence) {
        failIfInvalidArgument(sequence != null, "sequence must not be null");

        switch (sequence.operation()) {
            case REQ:
                if (available.getAsBoolean()) {
                    sender.accept(
                            DefaultUpstreamData.createSequenceAnswer(
                                    headSequence()));
                }
                return;

            case ANS:
            case NTF:
                long peerSequence = sequence.sequence().longValue();
                failIfIllegalState(
                        peerSequence <= headSequence(),
                        "Central TIMING_DATA sequence %s exceeds local LogBook head %s",
                        peerSequence,
                        headSequence());
                centralProcessedSequence = peerSequence;
                return;

            default:
                throw new IllegalArgumentException(
                        "Unsupported SEQ operation " + sequence.operation());
        }
    }

    /** Returns the current committed TIMING_DATA source head; empty means zero. */
    long headSequence() {
        return logBook.process(
                LogBookQueries.timingDataHead(),
                ReadConsistency.CURRENT);
    }

    /** Returns the latest peer processed position learned through SEQ. */
    synchronized long centralProcessedSequence() {
        return centralProcessedSequence;
    }

    /**
     * Starts one replay at the inclusive sequence requested by the peer.
     *
     * <p>A request for exactly currentHead+1 is the empty replay used when the peer is already
     * caught up. It completes the synchronization handoff immediately and enables live forwarding
     * from that sequence. A request further beyond the current head does not establish live state.</p>
     */
    synchronized void startReplay(UpstreamData.TimingDataRequest request) {
        failIfInvalidArgument(request != null, "request must not be null");

        long fromSequence = request.fromSequence();
        failIfInvalidArgument(
                fromSequence >= 1L
                        && fromSequence <= TimingRecord.MAX_SEQUENCE_NUMBER,
                "TIMING_DATA_REQ fromSequence is outside the supported range");

        replayActive = false;
        liveActive = false;

        long capturedHead = headSequence();
        if (fromSequence > capturedHead) {
            if (fromSequence == capturedHead + 1L) {
                enableLive(fromSequence);
                LOG.info(
                        "TIMING_DATA replay empty: node={}, fromSeq={}, head={}, liveFrom={}",
                        nodeId.value(),
                        fromSequence,
                        capturedHead,
                        fromSequence);
            } else {
                LOG.warn(
                        "TIMING_DATA replay not started: node={}, fromSeq={}, head={}, "
                                + "reason=request beyond next sequence",
                        nodeId.value(),
                        fromSequence,
                        capturedHead);
            }
            return;
        }

        nextReplaySequence = fromSequence;
        replayHead = capturedHead;
        replayActive = true;
        LOG.info(
                "TIMING_DATA replay started: node={}, fromSeq={}, head={}",
                nodeId.value(),
                fromSequence,
                capturedHead);
    }

    /** Reports whether the captured replay still has records to send. */
    synchronized boolean replayPending() {
        return replayActive;
    }

    /** Cancels only the active replay continuation after one inbound message failure. */
    synchronized void cancelReplay() {
        replayActive = false;
    }

    /** Clears replay and live state when the transport session ends. */
    synchronized void sessionUnavailable() {
        replayActive = false;
        liveActive = false;
    }

    /** Submits the next ordered replay batch without blocking NodeUpstream. */
    synchronized CompletableFuture<List<TimingRecord>> submitNextBatch() {
        failIfIllegalState(replayActive,
                "No TIMING_DATA replay is active");

        int limit =
                (int) Math.min(
                        (long) MAX_RECORDS_PER_BATCH,
                        replayHead - nextReplaySequence + 1L);
        long batchEnd = nextReplaySequence + limit - 1L;
        LOG.info(
                "TIMING_DATA replay LogBook read queued: node={}, fromSeq={}, toSeq={}",
                nodeId.value(),
                nextReplaySequence,
                batchEnd);

        return logBook.submit(
                LogBookQueries.timingRecordRange(
                        nextReplaySequence,
                        limit));
    }

    /** Validates and sends one completed ordered replay batch. */
    synchronized void sendNextBatch(List<TimingRecord> records) {
        failIfInvalidArgument(records != null, "records must not be null");

        validateBatch(
                records,
                nextReplaySequence,
                replayHead,
                "replay");

        TimingRecord last = records.get(records.size() - 1);
        sender.accept(
                DefaultUpstreamData.createTimingData(
                        records));

        if (last.sequenceNumber() >= replayHead) {
            replayActive = false;
            long liveFrom = replayHead + 1L;
            enableLive(liveFrom);
            LOG.info(
                    "TIMING_DATA replay completed: node={}, throughSeq={}, head={}, liveFrom={}",
                    nodeId.value(),
                    last.sequenceNumber(),
                    replayHead,
                    liveFrom);
        } else {
            nextReplaySequence = last.sequenceNumber() + 1L;
        }
    }

    /** Returns whether the replay handoff has completed and proactive live forwarding is enabled. */
    synchronized boolean liveReady() {
        return liveActive;
    }

    /** Reports whether the live cursor currently trails the authoritative LogBook head. */
    synchronized boolean livePending() {
        return liveActive && nextLiveSequence <= headSequence();
    }

    /** Reads the next bounded live suffix from the authoritative LogBook. */
    synchronized CompletableFuture<List<TimingRecord>> submitNextLiveBatch() {
        failIfIllegalState(liveActive,
                "Live TIMING_DATA forwarding is not active");

        return logBook.submit(
                LogBookQueries.timingRecordRange(
                        nextLiveSequence,
                        MAX_RECORDS_PER_BATCH));
    }

    /** Validates, sends and advances one live LogBook batch. */
    synchronized void sendNextLiveBatch(List<TimingRecord> records) {
        failIfInvalidArgument(records != null, "records must not be null");
        failIfIllegalState(liveActive,
                "Live TIMING_DATA forwarding is not active");

        validateBatch(
                records,
                nextLiveSequence,
                TimingRecord.MAX_SEQUENCE_NUMBER,
                "live forwarding");

        TimingRecord last = records.get(records.size() - 1);
        sender.accept(
                DefaultUpstreamData.createTimingData(
                        records));

        if (last.sequenceNumber() >= TimingRecord.MAX_SEQUENCE_NUMBER) {
            liveActive = false;
        } else {
            nextLiveSequence = last.sequenceNumber() + 1L;
        }
    }

    /** Enables live forwarding when another TimingData sequence can still exist. */
    private void enableLive(long fromSequence) {
        if (fromSequence > TimingRecord.MAX_SEQUENCE_NUMBER) {
            liveActive = false;
            return;
        }
        nextLiveSequence = fromSequence;
        liveActive = true;
    }

    /** Verifies one bounded contiguous LogBook batch before it crosses IF-06. */
    private void validateBatch(
            List<TimingRecord> records,
            long expectedFirstSequence,
            long maximumSequence,
            String operation) {

        failIfIllegalState(!records.isEmpty(),
                "LogBook returned no data for TIMING_DATA %s at sequence %s",
                operation,
                expectedFirstSequence);
        failIfIllegalState(records.size() <= MAX_RECORDS_PER_BATCH,
                "TIMING_DATA %s returned %s records; maximum is %s",
                operation,
                records.size(),
                MAX_RECORDS_PER_BATCH);

        long expected = expectedFirstSequence;
        for (TimingRecord record : records) {
            failIfIllegalState(record != null,
                    "TIMING_DATA %s returned a null record",
                    operation);
            failIfIllegalState(record.sequenceNumber() == expected,
                    "TIMING_DATA %s expected sequence %s but got %s",
                    operation,
                    expected,
                    record.sequenceNumber());
            failIfIllegalState(record.sequenceNumber() <= maximumSequence,
                    "TIMING_DATA %s crossed sequence boundary %s",
                    operation,
                    maximumSequence);
            expected++;
        }
    }
}
