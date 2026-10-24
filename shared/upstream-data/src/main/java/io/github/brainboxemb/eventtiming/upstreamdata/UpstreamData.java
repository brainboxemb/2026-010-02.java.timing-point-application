package io.github.brainboxemb.eventtiming.upstreamdata;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
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
 * Transport-independent immutable IF-06 packet.
 *
 * <p>One packet has one direction, one node, one delivery identity and exactly one semantic
 * message. Packet identity is deliberately separate from TIMING_DATA/LogBook sequence identity.</p>
 */
public final class UpstreamData {
    public static final int MAX_TIMING_DATA_RECORDS = 16;
    public static final int MAX_RACE_DATA_CHANGES = 16;

    private final Direction direction;
    private final NodeId timingNodeId;
    private final long packetId;
    private final Message message;

    public UpstreamData(
            Direction direction,
            NodeId timingNodeId,
            long packetId,
            Message message) {
        if (direction == null) {
            throw new IllegalArgumentException("direction must not be null");
        }
        if (timingNodeId == null) {
            throw new IllegalArgumentException("timingNodeId must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        if (packetId < 0L) {
            throw new IllegalArgumentException("packetId must be zero or positive");
        }

        if (message.family() == Family.PING) {
            if (packetId != 0L) {
                throw new IllegalArgumentException("PING requires packetId 0");
            }
        } else if (packetId == 0L) {
            throw new IllegalArgumentException("packetId 0 is reserved for PING");
        }

        requireDirection(direction, message.family());
        validateNodeScopedMessage(timingNodeId, message);

        this.direction = direction;
        this.timingNodeId = timingNodeId;
        this.packetId = packetId;
        this.message = message;
    }

    public Direction direction() {
        return direction;
    }

    /** Returns the node whose upstream protocol state this packet belongs to. */
    public NodeId timingNodeId() {
        return timingNodeId;
    }

    /** Returns the delivery/debug identity of this packet. PING uses zero. */
    public long packetId() {
        return packetId;
    }

    /** Returns the single semantic message carried by this packet. */
    public Message message() {
        return message;
    }

    /** Convenience dispatch value for the contained semantic message. */
    public Family family() {
        return message.family();
    }

    public enum Direction {
        UP_PACKET,
        DN_PACKET
    }

    public enum Family {
        TIMING_DATA,
        TIMING_DATA_REQ,
        SEQ,
        RACE_DATA,
        RECV,
        PING,
        STATUS
    }

    public enum ChangeOperation {
        SET,
        DEL
    }

    public enum SequenceOperation {
        REQ,
        ANS,
        NTF
    }

    public enum PingOperation {
        REQ,
        ANS
    }

    public enum PingMode {
        HB
    }

    /** Base for one semantic message carried by an IF-06 packet. */
    public abstract static class Message {
        private Message() {
        }

        public abstract Family family();
    }

    /** One TIMING_DATA transfer containing one or more scalar TimingRecords. */
    public static final class TimingDataTransfer extends Message {
        private final List<TimingRecord> records;

        public TimingDataTransfer(List<TimingRecord> records) {
            this.records = copyTimingData(records);
        }

        @Override
        public Family family() {
            return Family.TIMING_DATA;
        }

        public List<TimingRecord> records() {
            return records;
        }
    }

    /** One Central-to-node RACE_DATA change set. */
    public static final class RaceData extends Message {
        private final LocationId locationId;
        private final List<TeamAssignmentChange> teamAssignmentChanges;
        private final List<StageStartTimeChange> stageStartTimeChanges;

        public RaceData(
                LocationId locationId,
                List<TeamAssignmentChange> teamAssignmentChanges,
                List<StageStartTimeChange> stageStartTimeChanges) {

            if (locationId == null) {
                throw new IllegalArgumentException("RACE_DATA locationId must not be null");
            }
            this.locationId = locationId;
            this.teamAssignmentChanges = copyTeamAssignmentChanges(teamAssignmentChanges);
            this.stageStartTimeChanges = copyStageStartTimeChanges(stageStartTimeChanges);

            int changeCount =
                    this.teamAssignmentChanges.size()
                            + this.stageStartTimeChanges.size();
            if (changeCount == 0) {
                throw new IllegalArgumentException("RACE_DATA requires at least one change");
            }
            if (changeCount > MAX_RACE_DATA_CHANGES) {
                throw new IllegalArgumentException(
                        "RACE_DATA supports at most "
                                + MAX_RACE_DATA_CHANGES
                                + " changes");
            }
        }

        @Override
        public Family family() {
            return Family.RACE_DATA;
        }

        public LocationId locationId() {
            return locationId;
        }

        public List<TeamAssignmentChange> teamAssignmentChanges() {
            return teamAssignmentChanges;
        }

        public List<StageStartTimeChange> stageStartTimeChanges() {
            return stageStartTimeChanges;
        }
    }

    /** One request for retained TIMING_DATA beginning at an inclusive LogBook sequence. */
    public static final class TimingDataRequest extends Message {
        private final long fromSequence;

        public TimingDataRequest(long fromSequence) {
            this.fromSequence =
                    requirePositiveTimingSequence(
                            fromSequence,
                            "TIMING_DATA_REQ fromSequence");
        }

        @Override
        public Family family() {
            return Family.TIMING_DATA_REQ;
        }

        public long fromSequence() {
            return fromSequence;
        }
    }

    /** One TIMING_DATA position request, answer or notification. */
    public static final class Sequence extends Message {
        private final SequenceOperation operation;
        private final Long sequence;

        public Sequence(
                SequenceOperation operation,
                Long sequence) {
            if (operation == null) {
                throw new IllegalArgumentException("SEQ operation must not be null");
            }
            if (operation == SequenceOperation.REQ) {
                if (sequence != null) {
                    throw new IllegalArgumentException("SEQ REQ must not carry seq");
                }
            } else {
                if (sequence == null) {
                    throw new IllegalArgumentException(
                            "SEQ " + operation + " requires seq");
                }
                requireTimingPosition(sequence.longValue());
            }

            this.operation = operation;
            this.sequence = sequence;
        }

        @Override
        public Family family() {
            return Family.SEQ;
        }

        public SequenceOperation operation() {
            return operation;
        }

        /** Null only for REQ. */
        public Long sequence() {
            return sequence;
        }
    }

    /** One cumulative report of the last successfully processed normal DN_PACKET. */
    public static final class Receive extends Message {
        private final long receivedPacketId;

        public Receive(long receivedPacketId) {
            if (receivedPacketId < 0L) {
                throw new IllegalArgumentException(
                        "receivedPacketId must be zero or positive");
            }
            this.receivedPacketId = receivedPacketId;
        }

        @Override
        public Family family() {
            return Family.RECV;
        }

        public long receivedPacketId() {
            return receivedPacketId;
        }
    }

    /** One PING request or answer with optional compatibility fields around its required operation. */
    public static final class Ping extends Message {
        private final PingOperation operation;
        private final PingMode mode;
        private final String pingId;
        private final TimingTimestamp time;

        /** Creates a PING without message time for compatibility with older peers. */
        public Ping(PingOperation operation, PingMode mode, String pingId) {
            this(operation, mode, pingId, null);
        }

        public Ping(
                PingOperation operation,
                PingMode mode,
                String pingId,
                TimingTimestamp time) {
            if (operation == null) {
                throw new IllegalArgumentException("operation must not be null");
            }
            if (time != null && !time.isAbsolute()) {
                throw new IllegalArgumentException("PING time must be absolute UTC");
            }

            String normalizedPingId = pingId == null ? null : pingId.trim();
            if (normalizedPingId != null && normalizedPingId.isEmpty()) {
                throw new IllegalArgumentException("pingId must not be blank");
            }

            this.operation = operation;
            this.mode = mode;
            this.pingId = normalizedPingId;
            this.time = time;
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

        /** Optional absolute UTC creation/send time of this specific PING message. */
        public TimingTimestamp time() {
            return time;
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

    private static void requireDirection(
            Direction direction,
            Family family) {
        switch (family) {
            case TIMING_DATA:
            case RECV:
                if (direction != Direction.UP_PACKET) {
                    throw new IllegalArgumentException(
                            family + " requires UP_PACKET");
                }
                return;

            case TIMING_DATA_REQ:
            case RACE_DATA:
                if (direction != Direction.DN_PACKET) {
                    throw new IllegalArgumentException(
                            family + " requires DN_PACKET");
                }
                return;

            case SEQ:
            case PING:
            case STATUS:
                return;

            default:
                throw new IllegalArgumentException(
                        "Unsupported IF-06 message family " + family);
        }
    }

    private static void validateNodeScopedMessage(
            NodeId nodeId,
            Message message) {
        if (message.family() != Family.TIMING_DATA) {
            return;
        }

        TimingDataTransfer transfer = (TimingDataTransfer) message;
        for (TimingRecord record : transfer.records()) {
            if (record.nodeId() != nodeId.value().charAt(0)) {
                throw new IllegalArgumentException(
                        "TIMING_DATA record nodeId does not match packet nodeId");
            }
        }
    }

    private static long requirePositiveTimingSequence(
            long sequence,
            String name) {
        if (sequence < 1L || sequence > TimingRecord.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(
                    name + " must be in range 1.." + TimingRecord.MAX_SEQUENCE_NUMBER);
        }
        return sequence;
    }

    private static void requireTimingPosition(long sequence) {
        if (sequence < 0L || sequence > TimingRecord.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(
                    "SEQ position must be in range 0.." + TimingRecord.MAX_SEQUENCE_NUMBER);
        }
    }

    private static List<TimingRecord> copyTimingData(List<TimingRecord> records) {
        if (records == null || records.isEmpty()) {
            throw new IllegalArgumentException("TIMING_DATA requires at least one record");
        }
        if (records.size() > MAX_TIMING_DATA_RECORDS) {
            throw new IllegalArgumentException(
                    "TIMING_DATA supports at most "
                            + MAX_TIMING_DATA_RECORDS
                            + " records");
        }

        List<TimingRecord> copy = new ArrayList<TimingRecord>(records.size());
        for (TimingRecord record : records) {
            if (record == null) {
                throw new IllegalArgumentException("TIMING_DATA records must not contain null");
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
