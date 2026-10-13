package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
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
            List<? extends TimingData> records) {
        return new TimingDataValue(
                requireNodeId(nodeId),
                copyTimingData(nodeId, records));
    }

    public static UpstreamData.RaceData raceData(
            NodeId nodeId,
            long revision,
            List<UpstreamData.RaceAssignment> assignments) {
        return new RaceDataValue(
                requireNodeId(nodeId),
                requireReferenceRevision(revision),
                copyAssignments(assignments));
    }

    public static UpstreamData.StageStartTimes stageStartTimes(
            NodeId nodeId,
            long revision,
            List<UpstreamData.StageStartDefinition> starts) {
        return new StageStartTimesValue(
                requireNodeId(nodeId),
                requireReferenceRevision(revision),
                copyStageStarts(starts));
    }

    public static UpstreamData.Sync sync(
            NodeId nodeId,
            long timingSequence,
            Long raceDataRevision,
            Long stageStartTimesRevision) {
        return new SyncValue(
                requireNodeId(nodeId),
                requireSyncSequence(timingSequence),
                requireSyncRevision(raceDataRevision, "raceDataRevision"),
                requireSyncRevision(stageStartTimesRevision, "stageStartTimesRevision"));
    }

    public static UpstreamData.Ping ping(
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
        return new PingValue(operation, mode, normalizedId);
    }

    public static UpstreamData.RaceAssignment assignment(
            String registrationId,
            String teamId) {
        return new UpstreamData.RaceAssignment(
                new RegistrationId(registrationId),
                new TeamId(teamId));
    }

    public static UpstreamData.StageStartDefinition stageStart(
            String teamId,
            LocalTime startTime) {
        return new UpstreamData.StageStartDefinition(
                new TeamId(teamId), startTime);
    }

    public static UpstreamData.StageStartDefinition stageStart(
            String teamId,
            LocalTime startTime,
            LocalDate startDate) {
        return new UpstreamData.StageStartDefinition(
                new TeamId(teamId), startTime, startDate);
    }

    private static NodeId requireNodeId(NodeId nodeId) {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        return nodeId;
    }

    private static long requireReferenceRevision(long revision) {
        if (revision < 1L || revision > TimingData.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(
                    "reference revision must be in range 1.."
                            + TimingData.MAX_SEQUENCE_NUMBER);
        }
        return revision;
    }

    private static long requireSyncSequence(long sequence) {
        if (sequence < 0L || sequence > TimingData.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(
                    "timingSequence must be in range 0.."
                            + TimingData.MAX_SEQUENCE_NUMBER);
        }
        return sequence;
    }

    private static Long requireSyncRevision(Long revision, String name) {
        if (revision != null
                && (revision.longValue() < 0L
                        || revision.longValue() > TimingData.MAX_SEQUENCE_NUMBER)) {
            throw new IllegalArgumentException(
                    name + " must be in range 0.." + TimingData.MAX_SEQUENCE_NUMBER);
        }
        return revision;
    }

    private static List<TimingData> copyTimingData(
            NodeId nodeId,
            List<? extends TimingData> records) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException(
                    "TIMING_DATA requires at least one record");
        }
        List<TimingData> copy =
                new ArrayList<TimingData>(records.size());
        for (TimingData record : records) {
            if (record == null) {
                throw new IllegalArgumentException(
                        "TIMING_DATA records must not contain null");
            }
            if (!nodeId.equals(record.timingNodeId())) {
                throw new IllegalArgumentException(
                        "TIMING_DATA record nodeId does not match envelope nodeId");
            }
            copy.add(record);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<UpstreamData.RaceAssignment> copyAssignments(
            List<UpstreamData.RaceAssignment> assignments) {
        if (assignments == null) {
            throw new IllegalArgumentException("assignments must not be null");
        }
        List<UpstreamData.RaceAssignment> copy =
                new ArrayList<UpstreamData.RaceAssignment>(assignments.size());
        Set<RegistrationId> registrations =
                new LinkedHashSet<RegistrationId>();
        Set<TeamId> teams = new LinkedHashSet<TeamId>();
        for (UpstreamData.RaceAssignment assignment : assignments) {
            if (assignment == null) {
                throw new IllegalArgumentException(
                        "assignments must not contain null");
            }
            if (!registrations.add(assignment.registrationId())) {
                throw new IllegalArgumentException(
                        "duplicate RaceData registrationId " + assignment.registrationId());
            }
            if (!teams.add(assignment.teamId())) {
                throw new IllegalArgumentException(
                        "duplicate RaceData teamId " + assignment.teamId());
            }
            copy.add(assignment);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<UpstreamData.StageStartDefinition> copyStageStarts(
            List<UpstreamData.StageStartDefinition> starts) {
        if (starts == null) {
            throw new IllegalArgumentException("starts must not be null");
        }
        List<UpstreamData.StageStartDefinition> copy =
                new ArrayList<UpstreamData.StageStartDefinition>(starts.size());
        Set<TeamId> teams = new LinkedHashSet<TeamId>();
        for (UpstreamData.StageStartDefinition start : starts) {
            if (start == null) {
                throw new IllegalArgumentException(
                        "starts must not contain null");
            }
            if (!teams.add(start.teamId())) {
                throw new IllegalArgumentException(
                        "duplicate StageStartTimes teamId " + start.teamId());
            }
            copy.add(start);
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
        private final List<TimingData> records;

        private TimingDataValue(NodeId nodeId, List<TimingData> records) {
            super(nodeId);
            this.records = records;
        }

        @Override
        public Family family() {
            return Family.TIMING_DATA;
        }

        @Override
        public List<TimingData> records() {
            return records;
        }
    }

    private abstract static class ReferenceValue
            extends NodeValue
            implements UpstreamData.ReferenceData {
        private final long revision;

        private ReferenceValue(NodeId nodeId, long revision) {
            super(nodeId);
            this.revision = revision;
        }

        @Override
        public final Family family() {
            return Family.REFERENCE_DATA;
        }

        @Override
        public final long revision() {
            return revision;
        }
    }

    private static final class RaceDataValue
            extends ReferenceValue
            implements UpstreamData.RaceData {
        private final List<UpstreamData.RaceAssignment> assignments;

        private RaceDataValue(
                NodeId nodeId,
                long revision,
                List<UpstreamData.RaceAssignment> assignments) {
            super(nodeId, revision);
            this.assignments = assignments;
        }

        @Override
        public ReferenceDataset dataset() {
            return ReferenceDataset.RACE_DATA;
        }

        @Override
        public List<UpstreamData.RaceAssignment> assignments() {
            return assignments;
        }
    }

    private static final class StageStartTimesValue
            extends ReferenceValue
            implements UpstreamData.StageStartTimes {
        private final List<UpstreamData.StageStartDefinition> starts;

        private StageStartTimesValue(
                NodeId nodeId,
                long revision,
                List<UpstreamData.StageStartDefinition> starts) {
            super(nodeId, revision);
            this.starts = starts;
        }

        @Override
        public ReferenceDataset dataset() {
            return ReferenceDataset.STAGE_START_TIMES;
        }

        @Override
        public List<UpstreamData.StageStartDefinition> starts() {
            return starts;
        }
    }

    private static final class SyncValue
            extends NodeValue
            implements UpstreamData.Sync {
        private final long timingSequence;
        private final Long raceDataRevision;
        private final Long stageStartTimesRevision;

        private SyncValue(
                NodeId nodeId,
                long timingSequence,
                Long raceDataRevision,
                Long stageStartTimesRevision) {
            super(nodeId);
            this.timingSequence = timingSequence;
            this.raceDataRevision = raceDataRevision;
            this.stageStartTimesRevision = stageStartTimesRevision;
        }

        @Override
        public Family family() {
            return Family.SYNC;
        }

        @Override
        public long timingSequence() {
            return timingSequence;
        }

        @Override
        public Long raceDataRevision() {
            return raceDataRevision;
        }

        @Override
        public Long stageStartTimesRevision() {
            return stageStartTimesRevision;
        }
    }

    private static final class PingValue
            implements UpstreamData.Ping {
        private final PingOperation operation;
        private final PingMode mode;
        private final String pingId;

        private PingValue(
                PingOperation operation,
                PingMode mode,
                String pingId) {
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
