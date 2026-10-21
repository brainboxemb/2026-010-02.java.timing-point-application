package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Factory for the public/reference IF-06 profile.
 *
 * <p>The immutable storage is defined directly by {@link UpstreamData}. This class adds only
 * profile-specific restrictions and convenience construction; it has no hidden message-value
 * implementation hierarchy.</p>
 */
public final class DefaultUpstreamData {
    private DefaultUpstreamData() {
    }

    public static UpstreamData.TimingDataTransfer timingData(
            NodeId nodeId,
            List<TimingRecord> records) {

        return new UpstreamData.TimingDataTransfer(nodeId, records);
    }

    public static UpstreamData.RaceData raceData(
            NodeId nodeId,
            long sequence,
            List<UpstreamData.TeamAssignmentChange> teamAssignmentChanges,
            List<UpstreamData.StageStartTimeChange> stageStartTimeChanges) {

        return new UpstreamData.RaceData(
                nodeId,
                sequence,
                teamAssignmentChanges,
                stageStartTimeChanges);
    }

    public static UpstreamData.Sync sync(
            NodeId nodeId,
            UpstreamData.Stream stream,
            long sequence) {

        return new UpstreamData.Sync(nodeId, stream, sequence);
    }

    public static UpstreamData.Ack ack(
            NodeId nodeId,
            UpstreamData.Stream stream,
            long sequence) {

        if (stream != UpstreamData.Stream.RACE_DATA) {
            throw new IllegalArgumentException("public v1 ACK supports RACE_DATA only");
        }
        return new UpstreamData.Ack(nodeId, stream, sequence);
    }

    public static UpstreamData.Req req(
            NodeId nodeId,
            UpstreamData.Stream stream,
            long fromSequence) {

        return new UpstreamData.Req(nodeId, stream, fromSequence);
    }

    public static UpstreamData.Ping ping(
            NodeId nodeId,
            UpstreamData.PingOperation operation,
            UpstreamData.PingMode mode,
            String pingId) {

        return new UpstreamData.Ping(nodeId, operation, mode, pingId);
    }

    public static UpstreamData.TeamAssignmentChange teamAssignmentSet(
            String registrationId,
            String teamId) {

        return new UpstreamData.TeamAssignmentChange(
                UpstreamData.ChangeOperation.SET,
                new RegistrationId(registrationId),
                new TeamId(teamId));
    }

    public static UpstreamData.TeamAssignmentChange teamAssignmentDelete(String registrationId) {
        return new UpstreamData.TeamAssignmentChange(
                UpstreamData.ChangeOperation.DEL,
                new RegistrationId(registrationId),
                null);
    }

    public static UpstreamData.StageStartTimeChange stageStartSet(
            String teamId,
            LocalTime startTime) {

        return stageStartSet(teamId, TimingTimestamp.localTime(startTime));
    }

    public static UpstreamData.StageStartTimeChange stageStartSet(
            String teamId,
            LocalTime startTime,
            LocalDate startDate) {

        return stageStartSet(
                teamId,
                startDate == null
                        ? TimingTimestamp.localTime(startTime)
                        : TimingTimestamp.localDateTime(startDate, startTime));
    }

    public static UpstreamData.StageStartTimeChange stageStartSet(
            String teamId,
            TimingTimestamp start) {

        if (start == null) {
            throw new IllegalArgumentException("start must not be null");
        }
        return new UpstreamData.StageStartTimeChange(
                UpstreamData.ChangeOperation.SET,
                new TeamId(teamId),
                start);
    }

    public static UpstreamData.StageStartTimeChange stageStartDelete(String teamId) {
        return new UpstreamData.StageStartTimeChange(
                UpstreamData.ChangeOperation.DEL,
                new TeamId(teamId),
                null);
    }
}
