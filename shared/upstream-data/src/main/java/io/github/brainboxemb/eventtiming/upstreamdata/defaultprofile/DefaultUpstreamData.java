package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable value factory for the public/reference UpstreamData profile. */
public final class DefaultUpstreamData {
    private DefaultUpstreamData() {
    }

    public static UpstreamData.TimingDataTransfer timingData(
            NodeId nodeId,
            List<TimingRecord> records) {
        return new TimingDataValue(
                requireNodeId(nodeId),
                copyTimingData(nodeId, records));
    }

    public static UpstreamData.RaceData raceData(
            NodeId nodeId,
            long sequence,
            List<UpstreamData.TeamAssignmentChange> teamAssignmentChanges,
            List<UpstreamData.StageStartTimeChange> stageStartTimeChanges) {
        List<UpstreamData.TeamAssignmentChange> assignments =
                copyTeamAssignmentChanges(teamAssignmentChanges);
        List<UpstreamData.StageStartTimeChange> starts =
                copyStageStartTimeChanges(stageStartTimeChanges);
        if (assignments.isEmpty() && starts.isEmpty()) {
            throw new IllegalArgumentException(
                    "RACE_DATA requires at least one change");
        }
        return new RaceDataValue(
                requireNodeId(nodeId),
                requirePositiveSequence(sequence, "RaceData sequence"),
                assignments,
                starts);
    }

    public static UpstreamData.Sync sync(
            NodeId nodeId,
            UpstreamData.Stream stream,
            long sequence) {
        return new SyncValue(
                requireNodeId(nodeId),
                requireStream(stream),
                requireSyncSequence(sequence));
    }

    public static UpstreamData.Ack ack(
            NodeId nodeId,
            UpstreamData.Stream stream,
            long sequence) {
        if (stream != UpstreamData.Stream.RACE_DATA) {
            throw new IllegalArgumentException(
                    "public v1 ACK supports RACE_DATA only");
        }
        return new AckValue(
                requireNodeId(nodeId),
                stream,
                requirePositiveSequence(sequence, "ACK sequence"));
    }

    public static UpstreamData.Req req(
            NodeId nodeId,
            UpstreamData.Stream stream,
            long fromSequence) {
        return new ReqValue(
                requireNodeId(nodeId),
                requireStream(stream),
                requirePositiveSequence(fromSequence, "REQ fromSequence"));
    }

    public static UpstreamData.Ping ping(
            NodeId nodeId,
            UpstreamData.PingOperation operation,
            UpstreamData.PingMode mode,
            String pingId) {
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        String normalizedId = null;
        if (pingId != null) {
            normalizedId = pingId.trim();
            if (normalizedId.isEmpty()) {
                throw new IllegalArgumentException("pingId must not be blank");
            }
        }
        return new PingValue(
                requireNodeId(nodeId),
                operation,
                mode,
                normalizedId);
    }

    public static UpstreamData.TeamAssignmentChange teamAssignmentSet(
            String registrationId,
            String teamId) {
        return new UpstreamData.TeamAssignmentChange(
                UpstreamData.ChangeOperation.SET,
                new RegistrationId(registrationId),
                new TeamId(teamId));
    }

    public static UpstreamData.TeamAssignmentChange teamAssignmentDelete(
            String registrationId) {
        return new UpstreamData.TeamAssignmentChange(
                UpstreamData.ChangeOperation.DEL,
                new RegistrationId(registrationId),
                null);
    }

    public static UpstreamData.StageStartTimeChange stageStartSet(
            String teamId,
            LocalTime startTime) {
        return stageStartSet(
                teamId,
                TimingTimestamp.localTime(
                        startTime));
    }

    public static UpstreamData.StageStartTimeChange stageStartSet(
            String teamId,
            LocalTime startTime,
            LocalDate startDate) {
        return stageStartSet(
                teamId,
                startDate == null
                        ? TimingTimestamp.localTime(startTime)
                        : TimingTimestamp.localDateTime(
                                startDate,
                                startTime));
    }

    public static UpstreamData.StageStartTimeChange stageStartSet(
            String teamId,
            TimingTimestamp start) {
        if (start == null) {
            throw new IllegalArgumentException(
                    "start must not be null");
        }
        return new UpstreamData.StageStartTimeChange(
                UpstreamData.ChangeOperation.SET,
                new TeamId(teamId),
                start);
    }

    public static UpstreamData.StageStartTimeChange stageStartDelete(
            String teamId) {
        return new UpstreamData.StageStartTimeChange(
                UpstreamData.ChangeOperation.DEL,
                new TeamId(teamId),
                null);
    }

    private static NodeId requireNodeId(NodeId nodeId) {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        return nodeId;
    }

    private static UpstreamData.Stream requireStream(
            UpstreamData.Stream stream) {
        if (stream == null) {
            throw new IllegalArgumentException("stream must not be null");
        }
        return stream;
    }

    private static long requirePositiveSequence(
            long sequence,
            String name) {
        if (sequence < 1L || sequence > TimingRecord.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(
                    name + " must be in range 1.."
                            + TimingRecord.MAX_SEQUENCE_NUMBER);
        }
        return sequence;
    }

    private static long requireSyncSequence(long sequence) {
        if (sequence < 0L || sequence > TimingRecord.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(
                    "SYNC sequence must be in range 0.."
                            + TimingRecord.MAX_SEQUENCE_NUMBER);
        }
        return sequence;
    }

    private static List<TimingRecord> copyTimingData(
            NodeId nodeId,
            List<TimingRecord> records) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException(
                    "TIMING_DATA requires at least one record");
        }
        List<TimingRecord> copy =
                new ArrayList<TimingRecord>(records.size());
        for (TimingRecord record : records) {
            if (record == null) {
                throw new IllegalArgumentException(
                        "TIMING_DATA records must not contain null");
            }
            if (record.nodeId() != nodeId.value().charAt(0)) {
                throw new IllegalArgumentException(
                        "TIMING_DATA record nodeId does not match envelope nodeId");
            }
            copy.add(record);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<UpstreamData.TeamAssignmentChange>
            copyTeamAssignmentChanges(
                    List<UpstreamData.TeamAssignmentChange> changes) {
        if (changes == null) {
            return Collections.emptyList();
        }
        List<UpstreamData.TeamAssignmentChange> copy =
                new ArrayList<UpstreamData.TeamAssignmentChange>(changes.size());
        Set<RegistrationId> keys = new LinkedHashSet<RegistrationId>();
        for (UpstreamData.TeamAssignmentChange change : changes) {
            if (change == null) {
                throw new IllegalArgumentException(
                        "teamAssignmentChanges must not contain null");
            }
            if (!keys.add(change.registrationId())) {
                throw new IllegalArgumentException(
                        "duplicate teamAssignments regId "
                                + change.registrationId());
            }
            copy.add(change);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<UpstreamData.StageStartTimeChange>
            copyStageStartTimeChanges(
                    List<UpstreamData.StageStartTimeChange> changes) {
        if (changes == null) {
            return Collections.emptyList();
        }
        List<UpstreamData.StageStartTimeChange> copy =
                new ArrayList<UpstreamData.StageStartTimeChange>(changes.size());
        Set<TeamId> keys = new LinkedHashSet<TeamId>();
        for (UpstreamData.StageStartTimeChange change : changes) {
            if (change == null) {
                throw new IllegalArgumentException(
                        "stageStartTimeChanges must not contain null");
            }
            if (!keys.add(change.teamId())) {
                throw new IllegalArgumentException(
                        "duplicate stageStartTimes teamId " + change.teamId());
            }
            copy.add(change);
        }
        return Collections.unmodifiableList(copy);
    }

    private abstract static class NodeValue
            implements UpstreamData.NodeScoped {
        private final NodeId nodeId;

        private NodeValue(NodeId nodeId) {
            this.nodeId = nodeId;
        }

        @Override
        public final NodeId timingNodeId() {
            return nodeId;
        }
    }

    private static final class TimingDataValue
            extends NodeValue
            implements UpstreamData.TimingDataTransfer {
        private final List<TimingRecord> records;

        private TimingDataValue(NodeId nodeId, List<TimingRecord> records) {
            super(nodeId);
            this.records = records;
        }

        @Override
        public Family family() {
            return Family.TIMING_DATA;
        }

        @Override
        public List<TimingRecord> records() {
            return records;
        }
    }

    private static final class RaceDataValue
            extends NodeValue
            implements UpstreamData.RaceData {
        private final long sequence;
        private final List<UpstreamData.TeamAssignmentChange> teamAssignmentChanges;
        private final List<UpstreamData.StageStartTimeChange> stageStartTimeChanges;

        private RaceDataValue(
                NodeId nodeId,
                long sequence,
                List<UpstreamData.TeamAssignmentChange> teamAssignmentChanges,
                List<UpstreamData.StageStartTimeChange> stageStartTimeChanges) {
            super(nodeId);
            this.sequence = sequence;
            this.teamAssignmentChanges = teamAssignmentChanges;
            this.stageStartTimeChanges = stageStartTimeChanges;
        }

        @Override
        public Family family() {
            return Family.RACE_DATA;
        }

        @Override
        public long sequence() {
            return sequence;
        }

        @Override
        public List<UpstreamData.TeamAssignmentChange> teamAssignmentChanges() {
            return teamAssignmentChanges;
        }

        @Override
        public List<UpstreamData.StageStartTimeChange> stageStartTimeChanges() {
            return stageStartTimeChanges;
        }
    }

    private abstract static class StreamValue
            extends NodeValue {
        private final UpstreamData.Stream stream;
        private final long sequence;

        private StreamValue(
                NodeId nodeId,
                UpstreamData.Stream stream,
                long sequence) {
            super(nodeId);
            this.stream = stream;
            this.sequence = sequence;
        }

        final UpstreamData.Stream streamValue() {
            return stream;
        }

        final long sequenceValue() {
            return sequence;
        }
    }

    private static final class SyncValue
            extends StreamValue
            implements UpstreamData.Sync {
        private SyncValue(
                NodeId nodeId,
                UpstreamData.Stream stream,
                long sequence) {
            super(nodeId, stream, sequence);
        }

        @Override
        public Family family() {
            return Family.SYNC;
        }

        @Override
        public UpstreamData.Stream stream() {
            return streamValue();
        }

        @Override
        public long sequence() {
            return sequenceValue();
        }
    }

    private static final class AckValue
            extends StreamValue
            implements UpstreamData.Ack {
        private AckValue(
                NodeId nodeId,
                UpstreamData.Stream stream,
                long sequence) {
            super(nodeId, stream, sequence);
        }

        @Override
        public Family family() {
            return Family.ACK;
        }

        @Override
        public UpstreamData.Stream stream() {
            return streamValue();
        }

        @Override
        public long sequence() {
            return sequenceValue();
        }
    }

    private static final class ReqValue
            extends NodeValue
            implements UpstreamData.Req {
        private final UpstreamData.Stream stream;
        private final long fromSequence;

        private ReqValue(
                NodeId nodeId,
                UpstreamData.Stream stream,
                long fromSequence) {
            super(nodeId);
            this.stream = stream;
            this.fromSequence = fromSequence;
        }

        @Override
        public Family family() {
            return Family.REQ;
        }

        @Override
        public UpstreamData.Stream stream() {
            return stream;
        }

        @Override
        public long fromSequence() {
            return fromSequence;
        }
    }

    private static final class PingValue
            extends NodeValue
            implements UpstreamData.Ping {
        private final PingOperation operation;
        private final PingMode mode;
        private final String pingId;

        private PingValue(
                NodeId nodeId,
                PingOperation operation,
                PingMode mode,
                String pingId) {
            super(nodeId);
            this.operation = operation;
            this.mode = mode;
            this.pingId = pingId;
        }

        @Override
        public Family family() {
            return Family.PING;
        }

        @Override
        public PingOperation operation() {
            return operation;
        }

        @Override
        public PingMode mode() {
            return mode;
        }

        @Override
        public String pingId() {
            return pingId;
        }
    }
}
