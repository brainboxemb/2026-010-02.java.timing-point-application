package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Passive TimingNode-local TeamId to stage-start state. */
final class StageStartTimes {
    private final Map<TeamId, StartDefinition> starts =
            new LinkedHashMap<TeamId, StartDefinition>();

    StartDefinition startFor(TeamId teamId) {
        return starts.get(teamId);
    }

    Map<TeamId, StartDefinition> candidateAfter(
            List<UpstreamData.StageStartTimeChange> changes) {
        Map<TeamId, StartDefinition> candidate =
                new LinkedHashMap<TeamId, StartDefinition>(starts);

        for (UpstreamData.StageStartTimeChange change : changes) {
            switch (change.operation()) {
                case SET:
                    candidate.put(
                            change.teamId(),
                            new StartDefinition(
                                    change.startTime(),
                                    change.startDate()));
                    break;
                case DEL:
                    candidate.remove(change.teamId());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unsupported stageStartTimes operation "
                                    + change.operation());
            }
        }
        return candidate;
    }

    void replaceWith(Map<TeamId, StartDefinition> candidate) {
        starts.clear();
        starts.putAll(candidate);
    }

    static final class StartDefinition {
        private final LocalTime startTime;
        private final LocalDate startDate;

        StartDefinition(
                LocalTime startTime,
                LocalDate startDate) {
            if (startTime == null) {
                throw new IllegalArgumentException(
                        "startTime must not be null");
            }
            this.startTime = startTime;
            this.startDate = startDate;
        }

        LocalTime startTime() {
            return startTime;
        }

        LocalDate startDate() {
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
            StartDefinition that = (StartDefinition) other;
            return startTime.equals(that.startTime)
                    && Objects.equals(startDate, that.startDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(startTime, startDate);
        }
    }
}
