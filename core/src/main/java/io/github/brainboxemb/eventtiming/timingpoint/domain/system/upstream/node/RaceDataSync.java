package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Owns ordered Central-to-node RACE_DATA protocol state for one NodeUpstream. */
final class RaceDataSync {
    private final NodeId nodeId;
    private final NodeUpstream upstream;

    private long appliedSequence;
    private long applyingSequence;

    RaceDataSync(
            NodeId nodeId,
            NodeUpstream upstream) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(upstream != null, "upstream must not be null");
        this.nodeId = nodeId;
        this.upstream = upstream;
    }

    boolean beginApply(
            UpstreamData.RaceData raceData) {
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        failIfIllegalState(applyingSequence == 0L,
                "RACE_DATA sequence %s is already being applied",
                applyingSequence);

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
            upstream.send(
                    DefaultUpstreamData.req(
                            nodeId,
                            UpstreamData.Stream.RACE_DATA,
                            expectedSequence));
            return false;
        }

        applyingSequence = receivedSequence;
        return true;
    }

    void completeApply(
            long sequence) {
        failIfIllegalState(applyingSequence == sequence,
                "RACE_DATA completion sequence does not match pending apply");

        appliedSequence = sequence;
        applyingSequence = 0L;

        if (upstream.isAvailable()) {
            sendAck(sequence);
        }
    }

    void cancelApply() {
        applyingSequence = 0L;
    }

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
            upstream.send(
                    DefaultUpstreamData.req(
                            nodeId,
                            UpstreamData.Stream.RACE_DATA,
                            appliedSequence + 1L));
        } else if (sourceHead < appliedSequence) {
            throw new IllegalStateException(
                    "Central RACE_DATA source head "
                            + sourceHead
                            + " is behind already applied sequence "
                            + appliedSequence);
        }
    }

    private void sendAck(
            long sequence) {
        upstream.send(
                DefaultUpstreamData.ack(
                        nodeId,
                        UpstreamData.Stream.RACE_DATA,
                        sequence));
    }
}
