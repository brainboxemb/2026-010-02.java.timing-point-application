package io.github.brainboxemb.eventtiming.timingpoint.domain.node.data;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Active TeamId-to-stage-start data for one TimingNode. */
public final class StageStartTimes {
    private final Map<TeamId, StartDefinition> starts;

    public StageStartTimes() {
        this.starts =
                new LinkedHashMap<TeamId, StartDefinition>();
    }

    private StageStartTimes(
            Map<TeamId, StartDefinition> starts) {
        this.starts =
                new LinkedHashMap<TeamId, StartDefinition>(starts);
    }

    public StageStartTimes copy() {
        return new StageStartTimes(starts);
    }

    public StartDefinition startFor(
            TeamId teamId) {
        if (teamId == null) {
            throw new IllegalArgumentException(
                    "teamId must not be null");
        }
        return starts.get(teamId);
    }

    public void set(
            TeamId teamId,
            LocalTime startTime,
            LocalDate startDate) {
        if (teamId == null) {
            throw new IllegalArgumentException(
                    "teamId must not be null");
        }
        starts.put(
                teamId,
                new StartDefinition(
                        startTime,
                        startDate));
    }

    public void remove(
            TeamId teamId) {
        if (teamId == null) {
            throw new IllegalArgumentException(
                    "teamId must not be null");
        }
        starts.remove(teamId);
    }

    public void replaceWith(
            StageStartTimes updatedStartTimes) {
        if (updatedStartTimes == null) {
            throw new IllegalArgumentException(
                    "updatedStartTimes must not be null");
        }
        starts.clear();
        starts.putAll(updatedStartTimes.starts);
    }

    public static final class StartDefinition {
        private final LocalTime startTime;
        private final LocalDate startDate;

        public StartDefinition(
                LocalTime startTime,
                LocalDate startDate) {
            if (startTime == null) {
                throw new IllegalArgumentException(
                        "startTime must not be null");
            }
            this.startTime = startTime;
            this.startDate = startDate;
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
            if (!(other instanceof StartDefinition)) {
                return false;
            }
            StartDefinition that =
                    (StartDefinition) other;
            return startTime.equals(that.startTime)
                    && Objects.equals(
                            startDate,
                            that.startDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    startTime,
                    startDate);
        }
    }
}
