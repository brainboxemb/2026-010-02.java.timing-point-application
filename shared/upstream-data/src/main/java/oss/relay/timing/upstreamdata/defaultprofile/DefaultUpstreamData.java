package oss.relay.timing.upstreamdata.defaultprofile;

import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingTimestamp;
import oss.relay.timing.upstreamdata.UpstreamData;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Factory for the public/reference IF-06 profile.
 *
 * <p>The semantic packet/message storage is defined directly by {@link UpstreamData}.
 * This class owns only reference-profile construction conveniences.</p>
 */
public final class DefaultUpstreamData {
    private DefaultUpstreamData() {
    }

    /** Creates one SI-01-to-Central packet. */
    public static UpstreamData createUpPacket(
            NodeId nodeId,
            long packetNr,
            UpstreamData.Message message) {
        return new UpstreamData(
                UpstreamData.Direction.UP_PACKET,
                nodeId,
                packetNr,
                message);
    }

    /** Creates one Central-to-SI-01 packet. */
    public static UpstreamData createDnPacket(
            NodeId nodeId,
            long packetNr,
            UpstreamData.Message message) {
        return new UpstreamData(
                UpstreamData.Direction.DN_PACKET,
                nodeId,
                packetNr,
                message);
    }

    /** Creates one TIMING_DATA semantic message. */
    public static UpstreamData.TimingDataTransfer createTimingData(
            List<TimingRecord> records) {
        return new UpstreamData.TimingDataTransfer(records);
    }

    /** Creates one TEAM_ASSIGNMENTS semantic message. */
    public static UpstreamData.TeamAssignments createTeamAssignments(
            List<UpstreamData.TeamAssignmentChange> changes) {

        return new UpstreamData.TeamAssignments(changes);
    }

    /** Creates one STAGE_STARTS semantic message. */
    public static UpstreamData.StageStarts createStageStarts(
            List<UpstreamData.StageStartTimeChange> changes) {

        return new UpstreamData.StageStarts(changes);
    }

    /** Creates one TIMING_DATA replay request. */
    public static UpstreamData.TimingDataRequest createTimingDataRequest(
            long fromSequence) {
        return new UpstreamData.TimingDataRequest(fromSequence);
    }

    /** Creates one TIMING_DATA sequence request. */
    public static UpstreamData.Sequence createSequenceRequest() {
        return new UpstreamData.Sequence(
                UpstreamData.SequenceOperation.REQ,
                null);
    }

    /** Creates one TIMING_DATA sequence answer. */
    public static UpstreamData.Sequence createSequenceAnswer(
            long sequence) {
        return new UpstreamData.Sequence(
                UpstreamData.SequenceOperation.ANS,
                Long.valueOf(sequence));
    }

    /** Creates one TIMING_DATA sequence notification. */
    public static UpstreamData.Sequence createSequenceNotification(
            long sequence) {
        return new UpstreamData.Sequence(
                UpstreamData.SequenceOperation.NTF,
                Long.valueOf(sequence));
    }

    /** Creates one downstream receive-position report. */
    public static UpstreamData.Receive createReceive(
            long receivedPacketNr) {
        return new UpstreamData.Receive(receivedPacketNr);
    }

    /** Creates one compatibility PING value without message time. */
    public static UpstreamData.Ping createPing(
            UpstreamData.PingOperation operation,
            UpstreamData.PingMode mode,
            String pingId) {
        return new UpstreamData.Ping(operation, mode, pingId);
    }

    /** Creates one manual or heartbeat PING with its absolute UTC message time. */
    public static UpstreamData.Ping createPing(
            UpstreamData.PingOperation operation,
            UpstreamData.PingMode mode,
            String pingId,
            TimingTimestamp time) {
        return new UpstreamData.Ping(operation, mode, pingId, time);
    }

    /** Creates one team-assignment SET change. */
    public static UpstreamData.TeamAssignmentChange createTeamAssignmentSet(
            String registrationId,
            String teamId) {
        return new UpstreamData.TeamAssignmentChange(
                UpstreamData.ChangeOperation.SET,
                new RegistrationId(registrationId),
                new TeamId(teamId));
    }

    /** Creates one team-assignment DEL change. */
    public static UpstreamData.TeamAssignmentChange createTeamAssignmentDelete(
            String registrationId) {
        return new UpstreamData.TeamAssignmentChange(
                UpstreamData.ChangeOperation.DEL,
                new RegistrationId(registrationId),
                null);
    }

    /** Creates one time-only stage-start SET change. */
    public static UpstreamData.StageStartTimeChange createStageStartSet(
            String registrationId,
            LocalTime startTime) {
        return createStageStartSet(
                registrationId,
                TimingTimestamp.localTime(startTime));
    }

    /** Creates one date+time or time-only stage-start SET change. */
    public static UpstreamData.StageStartTimeChange createStageStartSet(
            String registrationId,
            LocalTime startTime,
            LocalDate startDate) {
        return createStageStartSet(
                registrationId,
                startDate == null
                        ? TimingTimestamp.localTime(startTime)
                        : TimingTimestamp.localDateTime(startDate, startTime));
    }

    /** Creates one stage-start SET change from an already parsed IF-06 timestamp. */
    public static UpstreamData.StageStartTimeChange createStageStartSet(
            String registrationId,
            TimingTimestamp start) {
        if (start == null) {
            throw new IllegalArgumentException("start must not be null");
        }
        return new UpstreamData.StageStartTimeChange(
                UpstreamData.ChangeOperation.SET,
                new RegistrationId(registrationId),
                start);
    }

    /** Creates one stage-start DEL change. */
    public static UpstreamData.StageStartTimeChange createStageStartDelete(
            String registrationId) {
        return new UpstreamData.StageStartTimeChange(
                UpstreamData.ChangeOperation.DEL,
                new RegistrationId(registrationId),
                null);
    }
}
