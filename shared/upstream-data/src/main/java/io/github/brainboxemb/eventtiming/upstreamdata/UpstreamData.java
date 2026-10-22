package io.github.brainboxemb.eventtiming.upstreamdata;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Transport-independent immutable IF-06 message value.
 *
 * <p>Every current IF-06 message is node-scoped, so the NodeId lives once in this base value.
 * Concrete message classes contain only the fields needed by that message family. There is no
 * secondary implementation hierarchy behind the public semantic types.</p>
 */
public abstract class UpstreamData {
    private final NodeId timingNodeId;

    private UpstreamData(NodeId timingNodeId) {
        if (timingNodeId == null) {
            throw new IllegalArgumentException("timingNodeId must not be null");
        }
        this.timingNodeId = timingNodeId;
    }

    /** Returns the node whose upstream protocol state this message belongs to. */
    public final NodeId timingNodeId() {
        return timingNodeId;
    }

    /** Returns the IF-06 message family used for wire dispatch. */
    public abstract Family family();

    public enum Family {
        TIMING_DATA,
        RACE_DATA,
        SYNC,
        ACK,
        REQ,
        PING,
        STATUS
    }

    public enum Stream {
        TIMING_DATA,
        RACE_DATA
    }

    public enum ChangeOperation {
        SET,
        DEL
    }

    public enum PingOperation {
        REQ,
        ANS
    }

    public enum PingMode {
        HB
    }

    /** One TIMING_DATA transfer containing one or more scalar TimingRecords. */
    public static final class TimingDataTransfer extends UpstreamData {
        private final List<TimingRecord> records;

        public TimingDataTransfer(NodeId nodeId, List<TimingRecord> records) {
            super(nodeId);
            this.records = copyTimingData(nodeId, records);
        }

        @Override
        public Family family() {
            return Family.TIMING_DATA;
        }

        public List<TimingRecord> records() {
            return records;
        }
    }

    /** One ordered Central-to-node RACE_DATA change set. */
    public static final class RaceData extends UpstreamData {
        private final long sequence;
        private final List<TeamAssignmentChange> teamAssignmentChanges;
        private final List<StageStartTimeChange> stageStartTimeChanges;

        public RaceData(
                NodeId nodeId,
                long sequence,
                List<TeamAssignmentChange> teamAssignmentChanges,
                List<StageStartTimeChange> stageStartTimeChanges) {

            super(nodeId);
            this.sequence = requirePositiveSequence(sequence, "RaceData sequence");
            this.teamAssignmentChanges = copyTeamAssignmentChanges(teamAssignmentChanges);
            this.stageStartTimeChanges = copyStageStartTimeChanges(stageStartTimeChanges);

            if (this.teamAssignmentChanges.isEmpty() && this.stageStartTimeChanges.isEmpty()) {
                throw new IllegalArgumentException("RACE_DATA requires at least one change");
            }
        }

        @Override
        public Family family() {
            return Family.RACE_DATA;
        }

        public long sequence() {
            return sequence;
        }

        public List<TeamAssignmentChange> teamAssignmentChanges() {
            return teamAssignmentChanges;
        }

        public List<StageStartTimeChange> stageStartTimeChanges() {
            return stageStartTimeChanges;
        }
    }

    /** One stream-head synchronization value. */
    public static final class Sync extends UpstreamData {
        private final Stream stream;
        private final long sequence;

        public Sync(NodeId nodeId, Stream stream, long sequence) {
            super(nodeId);
            this.stream = requireStream(stream);
            this.sequence = requireSyncSequence(sequence);
        }

        @Override
        public Family family() {
            return Family.SYNC;
        }

        public Stream stream() {
            return stream;
        }

        public long sequence() {
            return sequence;
        }
    }

    /** One cumulative acknowledgement. */
    public static final class Ack extends UpstreamData {
        private final Stream stream;
        private final long sequence;

        public Ack(NodeId nodeId, Stream stream, long sequence) {
            super(nodeId);
            this.stream = requireStream(stream);
            this.sequence = requirePositiveSequence(sequence, "ACK sequence");
        }

        @Override
        public Family family() {
            return Family.ACK;
        }

        public Stream stream() {
            return stream;
        }

        public long sequence() {
            return sequence;
        }
    }

    /** One replay request starting at an inclusive sequence. */
    public static final class Req extends UpstreamData {
        private final Stream stream;
        private final long fromSequence;

        public Req(NodeId nodeId, Stream stream, long fromSequence) {
            super(nodeId);
            this.stream = requireStream(stream);
            this.fromSequence = requirePositiveSequence(fromSequence, "REQ fromSequence");
        }

        @Override
        public Family family() {
            return Family.REQ;
        }

        public Stream stream() {
            return stream;
        }

        public long fromSequence() {
            return fromSequence;
        }
    }

    /** One PING request or answer, optionally carrying heartbeat mode and correlation id. */
    public static final class Ping extends UpstreamData {
        private final PingOperation operation;
        private final PingMode mode;
        private final String pingId;

        public Ping(NodeId nodeId, PingOperation operation, PingMode mode, String pingId) {
            super(nodeId);
            if (operation == null) {
                throw new IllegalArgumentException("operation must not be null");
            }

            String normalizedPingId = pingId == null ? null : pingId.trim();
            if (normalizedPingId != null && normalizedPingId.isEmpty()) {
                throw new IllegalArgumentException("pingId must not be blank");
            }

            this.operation = operation;
            this.mode = mode;
            this.pingId = normalizedPingId;
        }

        @Override
        public Family family() {
            return Family.PING;
        }

        public PingOperation operation() {
            return operation;
        }

        /** Null denotes the normal/manual form without a mode field. */
        public PingMode mode() {
            return mode;
        }

        /** Optional opaque correlation token. */
        public String pingId() {
            return pingId;
        }
    }

    /**
     * One incremental active RegistrationId-to-TeamId assignment change.
     *
     * <p>Whether a RegistrationId came from a historical reserve tag or another source is not
     * part of IF-06 semantics.</p>
     */
    public static final class TeamAssignmentChange {
        private final ChangeOperation operation;
        private final RegistrationId registrationId;
        private final TeamId teamId;

        public TeamAssignmentChange(
                ChangeOperation operation,
                RegistrationId registrationId,
                TeamId teamId) {

            if (operation == null) {
                throw new IllegalArgumentException("operation must not be null");
            }
            if (registrationId == null) {
                throw new IllegalArgumentException("registrationId must not be null");
            }
            if (operation == ChangeOperation.SET && teamId == null) {
                throw new IllegalArgumentException("SET requires teamId");
            }
            if (operation == ChangeOperation.DEL && teamId != null) {
                throw new IllegalArgumentException("DEL must not carry teamId");
            }

            this.operation = operation;
            this.registrationId = registrationId;
            this.teamId = teamId;
        }

        public ChangeOperation operation() {
            return operation;
        }

        public RegistrationId registrationId() {
            return registrationId;
        }

        public TeamId teamId() {
            return teamId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TeamAssignmentChange)) {
                return false;
            }
            TeamAssignmentChange that = (TeamAssignmentChange) other;
            return operation == that.operation
                    && registrationId.equals(that.registrationId)
                    && Objects.equals(teamId, that.teamId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operation, registrationId, teamId);
        }
    }

    /** One incremental RegistrationId-to-stage-start change. */
    public static final class StageStartTimeChange {
        private final ChangeOperation operation;
        private final RegistrationId registrationId;
        private final TimingTimestamp start;

        public StageStartTimeChange(
                ChangeOperation operation,
                RegistrationId registrationId,
                TimingTimestamp start) {

            if (operation == null) {
                throw new IllegalArgumentException("operation must not be null");
            }
            if (registrationId == null) {
                throw new IllegalArgumentException("registrationId must not be null");
            }
            if (operation == ChangeOperation.SET && start == null) {
                throw new IllegalArgumentException("SET requires start timestamp");
            }
            if (operation == ChangeOperation.DEL && start != null) {
                throw new IllegalArgumentException("DEL must not carry start timestamp");
            }

            this.operation = operation;
            this.registrationId = registrationId;
            this.start = start;
        }

        public ChangeOperation operation() {
            return operation;
        }

        public RegistrationId registrationId() {
            return registrationId;
        }

        public TimingTimestamp start() {
            return start;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StageStartTimeChange)) {
                return false;
            }
            StageStartTimeChange that = (StageStartTimeChange) other;
            return operation == that.operation
                    && registrationId.equals(that.registrationId)
                    && Objects.equals(start, that.start);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operation, registrationId, start);
        }
    }

    private static Stream requireStream(Stream stream) {
        if (stream == null) {
            throw new IllegalArgumentException("stream must not be null");
        }
        return stream;
    }

    private static long requirePositiveSequence(long sequence, String name) {
        if (sequence < 1L || sequence > TimingRecord.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(
                    name + " must be in range 1.." + TimingRecord.MAX_SEQUENCE_NUMBER);
        }
        return sequence;
    }

    private static long requireSyncSequence(long sequence) {
        if (sequence < 0L || sequence > TimingRecord.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(
                    "SYNC sequence must be in range 0.." + TimingRecord.MAX_SEQUENCE_NUMBER);
        }
        return sequence;
    }

    private static List<TimingRecord> copyTimingData(NodeId nodeId, List<TimingRecord> records) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("TIMING_DATA requires at least one record");
        }

        List<TimingRecord> copy = new ArrayList<TimingRecord>(records.size());
        for (TimingRecord record : records) {
            if (record == null) {
                throw new IllegalArgumentException("TIMING_DATA records must not contain null");
            }
            if (record.nodeId() != nodeId.value().charAt(0)) {
                throw new IllegalArgumentException(
                        "TIMING_DATA record nodeId does not match message nodeId");
            }
            copy.add(record);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<TeamAssignmentChange> copyTeamAssignmentChanges(
            List<TeamAssignmentChange> changes) {

        if (changes == null) {
            return Collections.emptyList();
        }

        List<TeamAssignmentChange> copy = new ArrayList<TeamAssignmentChange>(changes.size());
        Set<RegistrationId> keys = new LinkedHashSet<RegistrationId>();
        for (TeamAssignmentChange change : changes) {
            if (change == null) {
                throw new IllegalArgumentException("teamAssignmentChanges must not contain null");
            }
            if (!keys.add(change.registrationId())) {
                throw new IllegalArgumentException(
                        "duplicate teamAssignments regId " + change.registrationId());
            }
            copy.add(change);
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<StageStartTimeChange> copyStageStartTimeChanges(
            List<StageStartTimeChange> changes) {

        if (changes == null) {
            return Collections.emptyList();
        }

        List<StageStartTimeChange> copy = new ArrayList<StageStartTimeChange>(changes.size());
        Set<RegistrationId> keys = new LinkedHashSet<RegistrationId>();
        for (StageStartTimeChange change : changes) {
            if (change == null) {
                throw new IllegalArgumentException("stageStartTimeChanges must not contain null");
            }
            if (!keys.add(change.registrationId())) {
                throw new IllegalArgumentException(
                        "duplicate stageStartTimes regId " + change.registrationId());
            }
            copy.add(change);
        }
        return Collections.unmodifiableList(copy);
    }
}
