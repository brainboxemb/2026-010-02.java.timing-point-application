package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns ordered Central-to-node RACE_DATA protocol state for one NodeUpstream.
 *
 * <p>This object does not execute NodeService work. RaceDataTask first asks
 * whether a sequence may be applied, then calls NodeService directly. Only
 * after that CompletableFuture completes does the task commit the protocol
 * sequence here and send the cumulative ACK.</p>
 */
final class RaceDataSync {
    private final NodeId nodeId;
    private final UpstreamStream stream;

    private long appliedSequence;
    private long applyingSequence;

    RaceDataSync(
            NodeId nodeId,
            UpstreamStream stream) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        this.nodeId = nodeId;
        this.stream = stream;
    }

    /**
     * Starts the next contiguous RACE_DATA apply when the message is eligible.
     *
     * <p>A duplicate or older sequence is not applied again and receives the
     * current cumulative ACK. A gap is not applied and receives REQ for the
     * first missing sequence.</p>
     *
     * @return true only when RaceDataTask must call NodeService for this message
     */
    boolean beginApply(
            UpstreamData.RaceData raceData) {
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        failIfIllegalState(applyingSequence == 0L,
                "RACE_DATA sequence %s is already being applied", applyingSequence);

        long receivedSequence = raceData.sequence();
        failIfInvalidArgument(
                receivedSequence >= 1L
                        && receivedSequence <= TimingRecord.MAX_SEQUENCE_NUMBER,
                "RACE_DATA sequence is outside the supported range");

        long expectedSequence = appliedSequence + 1L;
        if (receivedSequence <= appliedSequence) {
            sendAck(appliedSequence);
            return false;
        }
        if (receivedSequence != expectedSequence) {
            stream.send(DefaultUpstreamData.req(
                    nodeId,
                    UpstreamData.Stream.RACE_DATA,
                    expectedSequence));
            return false;
        }

        applyingSequence = receivedSequence;
        return true;
    }

    /**
     * Commits one successfully applied sequence.
     *
     * <p>The local applied head advances even if the transport disconnected
     * while NodeService was working. In that case no ACK is sent now; reconnect
     * synchronization or a repeated sequence will expose the committed head.</p>
     */
    void completeApply(
            long sequence) {
        failIfIllegalState(applyingSequence == sequence,
                "RACE_DATA completion sequence does not match pending apply");

        appliedSequence = sequence;
        applyingSequence = 0L;

        if (stream.isAvailable()) {
            sendAck(sequence);
        }
    }

    /** Drops a failed/incomplete apply without advancing the contiguous applied head. */
    void cancelApply() {
        applyingSequence = 0L;
    }

    /** Reconciles Central's advertised RACE_DATA source head with local applied state. */
    void handleSync(
            UpstreamData.Sync sync) {
        failIfInvalidArgument(sync != null, "sync must not be null");
        if (sync.stream() != UpstreamData.Stream.RACE_DATA) {
            throw new UnsupportedOperationException(
                    "Inbound SYNC supports RACE_DATA only");
        }

        long sourceHead = sync.sequence();
        failIfInvalidArgument(
                sourceHead >= 0L
                        && sourceHead <= TimingRecord.MAX_SEQUENCE_NUMBER,
                "SYNC sequence is outside the supported range");

        if (sourceHead > appliedSequence) {
            stream.send(DefaultUpstreamData.req(
                    nodeId,
                    UpstreamData.Stream.RACE_DATA,
                    appliedSequence + 1L));
        } else if (sourceHead < appliedSequence) {
            throw new IllegalStateException(
                    "Central RACE_DATA source head " + sourceHead
                            + " is behind already applied sequence " + appliedSequence);
        }
    }

    private void sendAck(
            long sequence) {
        stream.send(DefaultUpstreamData.ack(
                nodeId,
                UpstreamData.Stream.RACE_DATA,
                sequence));
    }
}
