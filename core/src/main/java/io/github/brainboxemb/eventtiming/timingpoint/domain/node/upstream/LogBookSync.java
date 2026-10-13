package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.ArrayList;
import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * Synchronizes this TimingNode's committed LogBook with Central.
 *
 * <p>The LogBook is the TIMING_DATA source of truth. A REQ captures the current
 * source head once; records committed afterwards are intentionally excluded from
 * that replay. Replay is bounded to one batch per TimingNode serial-lane turn so
 * large recovery traffic cannot monopolize the node.</p>
 */
final class LogBookSync {
    private static final int REPLAY_BATCH_SIZE = 256;

    private final NodeId nodeId;
    private final UpstreamStream stream;
    private final LogBook logBook;

    private boolean replayActive;
    private long replayNextSequence;
    private long replayCapturedHead;

    LogBookSync(NodeId nodeId, UpstreamStream stream, LogBook logBook) {
        checkArgument(nodeId != null, "nodeId must not be null");
        checkArgument(stream != null, "stream must not be null");
        checkArgument(logBook != null, "logBook must not be null");
        this.nodeId = nodeId;
        this.stream = stream;
        this.logBook = logBook;
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
        return logBook.headSequence();
    }

    /**
     * Starts a replay at the inclusive requested sequence.
     *
     * <p>The captured head remains fixed for the complete replay.</p>
     */
    void processReq(UpstreamData.Req req) {
        checkArgument(req != null, "req must not be null");
        if (req.stream() != UpstreamData.Stream.TIMING_DATA) {
            throw new UnsupportedOperationException("Inbound REQ supports TIMING_DATA only");
        }

        long fromSequence = req.fromSequence();
        checkArgument(fromSequence >= 1L && fromSequence <= TimingData.MAX_SEQUENCE_NUMBER,
                "REQ fromSequence is outside the supported range");

        long capturedHead = headSequence();
        if (fromSequence > capturedHead) {
            replayActive = false;
            return;
        }

        replayNextSequence = fromSequence;
        replayCapturedHead = capturedHead;
        replayActive = true;
    }

    boolean replayPending() {
        return replayActive;
    }

    void cancelReplay() {
        replayActive = false;
    }

    /**
     * Sends at most one batch using references to existing committed records.
     */
    void processReplayBatch() {
        int limit = (int) Math.min(
                (long) REPLAY_BATCH_SIZE,
                replayCapturedHead - replayNextSequence + 1L);

        List<TimingData> records = new ArrayList<TimingData>(limit);
        logBook.visitRange(replayNextSequence, limit, records::add);

        if (records.isEmpty()) {
            replayActive = false;
        }
        checkState(!records.isEmpty(),
                "LogBook head %s advertised record %s but replay returned no data",
                replayCapturedHead, replayNextSequence);

        TimingData first = records.get(0);
        if (first.sequenceNumber() != replayNextSequence) {
            replayActive = false;
        }
        checkState(first.sequenceNumber() == replayNextSequence,
                "TimingData replay expected sequence %s but got %s",
                replayNextSequence, first.sequenceNumber());

        TimingData last = records.get(records.size() - 1);
        if (last.sequenceNumber() > replayCapturedHead) {
            replayActive = false;
        }
        checkState(last.sequenceNumber() <= replayCapturedHead,
                "TimingData replay crossed captured head %s", replayCapturedHead);

        stream.send(DefaultUpstreamData.timingData(nodeId, records));

        if (last.sequenceNumber() >= replayCapturedHead) {
            replayActive = false;
        } else {
            replayNextSequence = last.sequenceNumber() + 1L;
        }
    }
}
