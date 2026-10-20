package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;
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
    private final Function<UpstreamData.RaceData, CompletionStage<Void>> raceDataConsumer;

    /*
     * Highest contiguous Central sequence committed to live TeamAssignments and
     * StageStartTimes. This protocol state only changes on the Task Lane.
     */
    private long appliedSequence;
    private long applyingSequence;

    /** Creates RACE_DATA synchronization state over the Task Lane-owned state boundary. */
    UpstreamDataSync(
            NodeId nodeId,
            UpstreamStream stream,
            Function<UpstreamData.RaceData, CompletionStage<Void>> raceDataConsumer) {
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
     * the message has validated. The returned completion represents the node-local
 * application; the caller must invoke completeRaceData on its own protocol lane
 * before the cumulative ACK is sent.</p>
     */
    CompletionStage<Void> handleRaceData(UpstreamData.RaceData raceData) {
        failIfInvalidArgument(raceData != null, "raceData must not be null");

        long receivedSequence = raceData.sequence();
        failIfInvalidArgument(receivedSequence >= 1L && receivedSequence <= TimingRecord.MAX_SEQUENCE_NUMBER,
                "RACE_DATA sequence is outside the supported range");

        long expectedSequence = appliedSequence + 1L;
        if (receivedSequence <= appliedSequence) {
            sendAck(appliedSequence);
            return null;
        }
        if (receivedSequence != expectedSequence) {
            stream.send(DefaultUpstreamData.req(
                    nodeId,
                    UpstreamData.Stream.RACE_DATA,
                    expectedSequence));
            return null;
        }

        applyingSequence = receivedSequence;
        CompletionStage<Void> completion =
                raceDataConsumer.apply(raceData);
        if (completion == null) {
            applyingSequence = 0L;
            throw new IllegalStateException(
                    "RACE_DATA consumer returned no completion");
        }
        return completion;
    }

    /** Commits one asynchronously applied RACE_DATA sequence and only then ACKs it. */
    void completeRaceData(long sequence) {
        if (applyingSequence != sequence) {
            throw new IllegalStateException(
                    "RACE_DATA completion sequence does not match pending apply");
        }
        appliedSequence = sequence;
        applyingSequence = 0L;
        sendAck(sequence);
    }

    /** Drops an unfinished apply after stream/task failure without advancing protocol state. */
    void cancelPending() {
        applyingSequence = 0L;
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
