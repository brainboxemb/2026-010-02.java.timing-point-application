package io.github.brainboxemb.eventtiming.upstreamdata;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.util.List;
import java.util.Objects;

/**
 * Transport-independent IF-06 semantic message contract.
 *
 * <p>TimingData keeps its existing IF-05/LogBook source sequence. RaceData is
 * the independently ordered Central-System-to-TimingNode stream. All v1
 * messages that describe or probe node state, including PING, are node-scoped.</p>
 */
public interface UpstreamData {

    Family family();

    enum Family {
        TIMING_DATA,
        RACE_DATA,
        SYNC,
        ACK,
        REQ,
        PING,
        STATUS
    }

    enum Stream {
        TIMING_DATA,
        RACE_DATA
    }

    enum ChangeOperation {
        SET,
        DEL
    }

    enum PingOperation {
        REQ,
        ANS
    }

    enum PingMode {
        HB
    }

    interface NodeScoped extends UpstreamData {
        NodeId timingNodeId();
    }

    interface TimingDataTransfer extends NodeScoped {
        List<TimingData> records();
    }

    interface RaceData extends NodeScoped {
        long sequence();

        List<TeamAssignmentChange> teamAssignmentChanges();

        List<StageStartTimeChange> stageStartTimeChanges();
    }

    interface Sync extends NodeScoped {
        Stream stream();

        long sequence();
    }

    interface Ack extends NodeScoped {
        Stream stream();

        long sequence();
    }

    interface Req extends NodeScoped {
        Stream stream();

        long fromSequence();
    }

    interface Ping extends NodeScoped {
        PingOperation operation();

        /** Null denotes the normal/manual form without a mode field. */
        PingMode mode();

        /** Optional opaque correlation token. */
        String pingId();
    }

    /**
     * One incremental active RegistrationId-to-TeamId assignment change.
     *
     * <p>This contract is intentionally generic. Whether a RegistrationId came
     * from a historical "reserve tag" or another dynamically assigned source is
     * not part of IF-06 semantics.</p>
     */
    final class TeamAssignmentChange {
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
            TeamAssignmentChange that =
                    (TeamAssignmentChange) other;
            return operation == that.operation
                    && registrationId.equals(that.registrationId)
                    && Objects.equals(teamId, that.teamId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operation, registrationId, teamId);
        }
    }

    /**
     * One incremental TeamId-to-stage-start change.
     *
     * <p>The wire profile may carry an absolute timestamp, date+time or time-only
     * value. UpstreamData preserves that boundary information. A TimingNode normalizes
     * the SET to its absolute UTC time basis when the ordered RACE_DATA message is
     * applied; partial civil time is not retained in live RaceDataState.</p>
     */
    final class StageStartTimeChange {
        private final ChangeOperation operation;
        private final TeamId teamId;
        private final TimingTimestamp start;

        public StageStartTimeChange(
                ChangeOperation operation,
                TeamId teamId,
                TimingTimestamp start) {
            if (operation == null) {
                throw new IllegalArgumentException(
                        "operation must not be null");
            }
            if (teamId == null) {
                throw new IllegalArgumentException(
                        "teamId must not be null");
            }
            if (operation == ChangeOperation.SET && start == null) {
                throw new IllegalArgumentException(
                        "SET requires start timestamp");
            }
            if (operation == ChangeOperation.DEL && start != null) {
                throw new IllegalArgumentException(
                        "DEL must not carry start timestamp");
            }
            this.operation = operation;
            this.teamId = teamId;
            this.start = start;
        }

        public ChangeOperation operation() {
            return operation;
        }

        public TeamId teamId() {
            return teamId;
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
            StageStartTimeChange that =
                    (StageStartTimeChange) other;
            return operation == that.operation
                    && teamId.equals(that.teamId)
                    && Objects.equals(start, that.start);
        }

        @Override
        public int hashCode() {
            return Objects.hash(operation, teamId, start);
        }
    }
}
