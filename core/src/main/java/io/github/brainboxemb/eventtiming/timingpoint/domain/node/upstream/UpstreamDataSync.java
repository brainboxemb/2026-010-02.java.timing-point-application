package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TeamAssignments;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Synchronizes Central-owned RACE_DATA into one TimingNode.
 *
 * <p>The helper owns the highest contiguous RACE_DATA sequence applied locally.
 * It does not own a worker: every method is called while the TimingNode serial
 * lane is current. TIMING_DATA/LogBook synchronization is deliberately handled
 * by {@link LogBookSync}.</p>
 */
final class UpstreamDataSync {
    private final NodeId nodeId;
    private final UpstreamStream stream;
    private final EventData eventData;
    private final TeamAssignments teamAssignments;
    private final StageStartTimes stageStartTimes;

    /*
     * Highest contiguous Central sequence committed to live TeamAssignments and
     * StageStartTimes. This protocol state only changes on the node serial lane.
     */
    private long appliedSequence;

    UpstreamDataSync(
            NodeId nodeId,
            UpstreamStream stream,
            EventData eventData,
            TeamAssignments teamAssignments,
            StageStartTimes stageStartTimes) {
        checkArgument(nodeId != null, "nodeId must not be null");
        checkArgument(stream != null, "stream must not be null");
        checkArgument(eventData != null, "eventData must not be null");
        checkArgument(teamAssignments != null, "teamAssignments must not be null");
        checkArgument(stageStartTimes != null, "stageStartTimes must not be null");
        this.nodeId = nodeId;
        this.stream = stream;
        this.eventData = eventData;
        this.teamAssignments = teamAssignments;
        this.stageStartTimes = stageStartTimes;
    }

    /**
     * Applies one ordered RACE_DATA update transactionally.
     *
     * <p>Duplicate/old data receives a cumulative ACK. A gap receives REQ for
     * the first missing sequence. Live state changes only after every change in
     * the message has validated.</p>
     */
    void processRaceData(UpstreamData.RaceData raceData) {
        checkArgument(raceData != null, "raceData must not be null");

        long receivedSequence = raceData.sequence();
        checkArgument(receivedSequence >= 1L && receivedSequence <= TimingData.MAX_SEQUENCE_NUMBER,
                "RACE_DATA sequence is outside the supported range");

        if (receivedSequence <= appliedSequence) {
            sendAck(appliedSequence);
            return;
        }

        long expectedSequence = appliedSequence + 1L;
        if (receivedSequence != expectedSequence) {
            stream.send(DefaultUpstreamData.req(
                    nodeId,
                    UpstreamData.Stream.RACE_DATA,
                    expectedSequence));
            return;
        }

        TeamAssignments updatedAssignments = teamAssignments.copy();
        StageStartTimes updatedStartTimes = stageStartTimes.copy();

        applyTeamAssignmentChanges(updatedAssignments, raceData);
        applyStageStartChanges(updatedStartTimes, raceData);
        updatedAssignments.validate();

        teamAssignments.replaceWith(updatedAssignments);
        stageStartTimes.replaceWith(updatedStartTimes);
        appliedSequence = receivedSequence;
        sendAck(receivedSequence);
    }

    /**
     * Reconciles Central's advertised RACE_DATA head with local applied state.
     */
    void processSync(UpstreamData.Sync sync) {
        checkArgument(sync != null, "sync must not be null");
        if (sync.stream() != UpstreamData.Stream.RACE_DATA) {
            throw new UnsupportedOperationException("Inbound SYNC supports RACE_DATA only");
        }

        long sourceHead = sync.sequence();
        checkArgument(sourceHead >= 0L && sourceHead <= TimingData.MAX_SEQUENCE_NUMBER,
                "SYNC sequence is outside the supported range");

        if (sourceHead == appliedSequence) {
            return;
        }

        if (sourceHead > appliedSequence) {
            stream.send(DefaultUpstreamData.req(
                    nodeId,
                    UpstreamData.Stream.RACE_DATA,
                    appliedSequence + 1L));
            return;
        }

        throw new IllegalStateException(
                "Central RACE_DATA source head " + sourceHead
                        + " is behind already applied sequence " + appliedSequence);
    }

    TeamId teamIdFor(RegistrationId registrationId) {
        checkArgument(registrationId != null, "registrationId must not be null");
        TeamId fixed = eventData.teamIdFor(registrationId);
        return fixed != null ? fixed : teamAssignments.teamIdFor(registrationId);
    }

    TimingTimestamp stageStartTimeFor(TeamId teamId) {
        return stageStartTimes.startFor(teamId);
    }

    private void sendAck(long sequence) {
        stream.send(DefaultUpstreamData.ack(
                nodeId,
                UpstreamData.Stream.RACE_DATA,
                sequence));
    }

    private void applyTeamAssignmentChanges(
            TeamAssignments updatedAssignments,
            UpstreamData.RaceData raceData) {
        checkArgument(raceData.teamAssignmentChanges() != null,
                "team assignment changes must not be null");

        for (UpstreamData.TeamAssignmentChange change : raceData.teamAssignmentChanges()) {
            checkArgument(change != null, "team assignment change must not be null");

            switch (change.operation()) {
                case SET:
                    checkArgument(eventData.teamIdFor(change.registrationId()) == null,
                            "active assignment cannot override fixed EventData TeamId for %s",
                            change.registrationId());
                    updatedAssignments.assign(change.registrationId(), change.teamId());
                    break;

                case DEL:
                    updatedAssignments.remove(change.registrationId());
                    break;

                default:
                    throw new IllegalArgumentException(
                            "unsupported team assignment operation " + change.operation());
            }
        }
    }

    private static void applyStageStartChanges(
            StageStartTimes updatedStartTimes,
            UpstreamData.RaceData raceData) {
        checkArgument(raceData.stageStartTimeChanges() != null,
                "stage start changes must not be null");

        for (UpstreamData.StageStartTimeChange change : raceData.stageStartTimeChanges()) {
            checkArgument(change != null, "stage start change must not be null");

            switch (change.operation()) {
                case SET:
                    updatedStartTimes.set(change.teamId(), change.start());
                    break;

                case DEL:
                    updatedStartTimes.remove(change.teamId());
                    break;

                default:
                    throw new IllegalArgumentException(
                            "unsupported stage start operation " + change.operation());
            }
        }
    }
}
