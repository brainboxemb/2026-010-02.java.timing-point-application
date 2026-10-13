package io.github.brainboxemb.eventtiming.upstreamdata;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/**
 * Transport-independent IF-06 semantic message contract.
 *
 * <p>UpstreamData wraps existing semantic data; it does not create another
 * TimingData identity or source-order model.</p>
 */
public interface UpstreamData {

    Family family();

    enum Family {
        TIMING_DATA,
        REFERENCE_DATA,
        SYNC,
        PING,
        STATUS
    }

    enum ReferenceDataset {
        RACE_DATA,
        STAGE_START_TIMES
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

    interface ReferenceData extends NodeScoped {
        ReferenceDataset dataset();
        long revision();
    }

    interface RaceData extends ReferenceData {
        List<RaceAssignment> assignments();
    }

    interface StageStartTimes extends ReferenceData {
        List<StageStartDefinition> starts();
    }

    interface Sync extends NodeScoped {
        long timingSequence();
        Long raceDataRevision();
        Long stageStartTimesRevision();
    }

    interface Ping extends UpstreamData {
        PingOperation operation();
        PingMode mode();
        String pingId();
    }

    final class RaceAssignment {
        private final RegistrationId registrationId;
        private final TeamId teamId;

        public RaceAssignment(RegistrationId registrationId, TeamId teamId) {
            if (registrationId == null) {
                throw new IllegalArgumentException("registrationId must not be null");
            }
            if (teamId == null) {
                throw new IllegalArgumentException("teamId must not be null");
            }
            this.registrationId = registrationId;
            this.teamId = teamId;
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
            if (!(other instanceof RaceAssignment)) {
                return false;
            }
            RaceAssignment that = (RaceAssignment) other;
            return registrationId.equals(that.registrationId)
                    && teamId.equals(that.teamId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(registrationId, teamId);
        }

        @Override
        public String toString() {
            return registrationId + "->" + teamId;
        }
    }

    final class StageStartDefinition {
        private final TeamId teamId;
        private final LocalTime startTime;
        private final LocalDate startDate;

        public StageStartDefinition(TeamId teamId, LocalTime startTime) {
            this(teamId, startTime, null);
        }

        public StageStartDefinition(
                TeamId teamId,
                LocalTime startTime,
                LocalDate startDate) {
            if (teamId == null) {
                throw new IllegalArgumentException("teamId must not be null");
            }
            if (startTime == null) {
                throw new IllegalArgumentException("startTime must not be null");
            }
            this.teamId = teamId;
            this.startTime = startTime;
            this.startDate = startDate;
        }

        public TeamId teamId() {
            return teamId;
        }

        public LocalTime startTime() {
            return startTime;
        }

        public LocalDate startDate() {
            return startDate;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof StageStartDefinition)) {
                return false;
            }
            StageStartDefinition that = (StageStartDefinition) other;
            return teamId.equals(that.teamId)
                    && startTime.equals(that.startTime)
                    && Objects.equals(startDate, that.startDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(teamId, startTime, startDate);
        }

        @Override
        public String toString() {
            return teamId + "@" + (startDate == null ? "" : startDate + "T") + startTime;
        }
    }
}
