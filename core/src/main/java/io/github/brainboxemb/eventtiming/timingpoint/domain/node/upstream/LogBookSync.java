package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookProcessor;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * Synchronizes this TimingNode's committed LogBook with Central.
 *
 * <p>The LogBook is the TIMING_DATA source of truth. A REQ captures the current
 * source head once; records committed afterwards are intentionally excluded from
 * that synchronization run. Records are sent in batches of at most 256 per
 * TimingNode serial-lane turn so a large synchronization cannot monopolize the
 * node.</p>
 */
final class LogBookSync {
    private static final int MAX_RECORDS_PER_BATCH = 256;

    private final NodeId nodeId;
    private final UpstreamStream stream;
    private final LogBookProcessor timingDataHistory;

    private boolean syncActive;
    private long nextSequence;
    private long syncHead;

    LogBookSync(NodeId nodeId, UpstreamStream stream, LogBookProcessor timingDataHistory) {
        checkArgument(nodeId != null, "nodeId must not be null");
        checkArgument(stream != null, "stream must not be null");
        checkArgument(timingDataHistory != null, "timingDataHistory must not be null");
        this.nodeId = nodeId;
        this.stream = stream;
        this.timingDataHistory = timingDataHistory;
    }

    /** Sends the current TIMING_DATA source head. Empty LogBook means sequence 0. */
    void sendSync() {
        if (stream.isAvailable()) {
            stream.send(DefaultUpstreamData.sync(
                    nodeId,
                    UpstreamData.Stream.TIMING_DATA,
                    headSequence()));
        }
    }

    long headSequence() {
        return timingDataHistory.timingDataHead();
    }

    /**
     * Starts LogBook synchronization at the inclusive requested sequence.
     *
     * <p>The captured head remains fixed for the complete synchronization run.</p>
     */
    void handleReq(UpstreamData.Req req) {
        checkArgument(req != null, "req must not be null");
        if (req.stream() != UpstreamData.Stream.TIMING_DATA) {
            throw new UnsupportedOperationException("Inbound REQ supports TIMING_DATA only");
        }

        long fromSequence = req.fromSequence();
        checkArgument(fromSequence >= 1L && fromSequence <= TimingData.MAX_SEQUENCE_NUMBER,
                "REQ fromSequence is outside the supported range");

        long capturedHead = headSequence();
        if (fromSequence > capturedHead) {
            syncActive = false;
            return;
        }

        nextSequence = fromSequence;
        syncHead = capturedHead;
        syncActive = true;
    }

    boolean syncPending() {
        return syncActive;
    }

    void cancelSync() {
        syncActive = false;
    }

    /**
     * Sends the next batch using references to existing committed records.
     *
     * <p>At most 256 records are sent in one TimingNode serial-lane turn.</p>
     */
    void sendNextBatch() {
        int limit = (int) Math.min(
                (long) MAX_RECORDS_PER_BATCH,
                syncHead - nextSequence + 1L);

        List<TimingData> records =
                timingDataHistory.timingDataRange(nextSequence, limit);

        if (records.isEmpty()) {
            syncActive = false;
        }
        checkState(!records.isEmpty(),
                "LogBook head %s advertised record %s but synchronization returned no data",
                syncHead, nextSequence);

        TimingData first = records.get(0);
        if (first.sequenceNumber() != nextSequence) {
            syncActive = false;
        }
        checkState(first.sequenceNumber() == nextSequence,
                "TimingData synchronization expected sequence %s but got %s",
                nextSequence, first.sequenceNumber());

        TimingData last = records.get(records.size() - 1);
        if (last.sequenceNumber() > syncHead) {
            syncActive = false;
        }
        checkState(last.sequenceNumber() <= syncHead,
                "TimingData synchronization crossed captured head %s", syncHead);

        stream.send(DefaultUpstreamData.timingData(nodeId, records));

        if (last.sequenceNumber() >= syncHead) {
            syncActive = false;
        } else {
            nextSequence = last.sequenceNumber() + 1L;
        }
    }
}
