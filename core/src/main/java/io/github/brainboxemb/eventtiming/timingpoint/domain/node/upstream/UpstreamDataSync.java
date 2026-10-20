package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.function.Consumer;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Synchronizes Central-owned RACE_DATA into one TimingNode.
 *
 * <p>The helper owns the highest contiguous RACE_DATA sequence applied locally.
 * It does not own a worker: every state-changing method is called while the
 * TimingNode Task Lane is current. TIMING_DATA synchronization is handled by
 * {@link LogBookSync} on that same Task Lane; ordered LogBook reads yield while
 * the LogBook Data Lane completes them.</p>
 */
final class UpstreamDataSync {
    private final NodeId nodeId;
    private final UpstreamStream stream;
    private final Consumer<UpstreamData.RaceData> raceDataConsumer;

    /*
     * Highest contiguous Central sequence committed to live TeamAssignments and
     * StageStartTimes. This protocol state only changes on the Task Lane.
     */
    private long appliedSequence;

    /** Creates RACE_DATA synchronization state over the Task Lane-owned state boundary. */
    UpstreamDataSync(NodeId nodeId, UpstreamStream stream, Consumer<UpstreamData.RaceData> raceDataConsumer) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(raceDataConsumer != null, "raceDataConsumer must not be null");
        this.nodeId = nodeId;
        this.stream = stream;
        this.raceDataConsumer = raceDataConsumer;
    }

    /**
     * Applies one ordered RACE_DATA update transactionally.
     *
     * <p>Duplicate/old data receives a cumulative ACK. A gap receives REQ for
     * the first missing sequence. Live state changes only after every change in
     * the message has validated.</p>
     */
    void handleRaceData(UpstreamData.RaceData raceData) {
        failIfInvalidArgument(raceData != null, "raceData must not be null");

        long receivedSequence = raceData.sequence();
        failIfInvalidArgument(receivedSequence >= 1L && receivedSequence <= TimingRecord.MAX_SEQUENCE_NUMBER,
                "RACE_DATA sequence is outside the supported range");

        long expectedSequence = appliedSequence + 1L;
        if (receivedSequence <= appliedSequence) {
            sendAck(appliedSequence);
        } else if (receivedSequence != expectedSequence) {
            stream.send(DefaultUpstreamData.req(
                    nodeId,
                    UpstreamData.Stream.RACE_DATA,
                    expectedSequence));
        } else {
            // RaceData is Task Lane-owned. ACK only after the complete change set
            // validated and was atomically applied to the current task state.
            raceDataConsumer.accept(raceData);
            appliedSequence = receivedSequence;
            sendAck(receivedSequence);
        }
    }

    /**
     * Reconciles Central's advertised RACE_DATA head with local applied state.
     */
    void handleSync(UpstreamData.Sync sync) {
        failIfInvalidArgument(sync != null, "sync must not be null");
        if (sync.stream() != UpstreamData.Stream.RACE_DATA) {
            throw new UnsupportedOperationException("Inbound SYNC supports RACE_DATA only");
        }

        long sourceHead = sync.sequence();
        failIfInvalidArgument(sourceHead >= 0L && sourceHead <= TimingRecord.MAX_SEQUENCE_NUMBER,
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

    /** Sends the cumulative acknowledgement for the highest locally applied RACE_DATA sequence. */
    private void sendAck(long sequence) {
        stream.send(DefaultUpstreamData.ack(
                nodeId,
                UpstreamData.Stream.RACE_DATA,
                sequence));
    }

}
