package io.github.brainboxemb.eventtiming.timingpoint.domain.node.data;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Active TeamId-to-stage-start data for one TimingNode.
 *
 * <p>The value is a TimingTimestamp rather than separate LocalTime/LocalDate
 * fields. The timestamp itself records whether only local time, local date/time
 * or an absolute instant is known. This state object does not invent missing
 * date or zone context.</p>
 */
public final class StageStartTimes {
    private final Map<TeamId, TimingTimestamp> starts;

    public StageStartTimes() {
        this.starts =
                new LinkedHashMap<TeamId, TimingTimestamp>();
    }

    private StageStartTimes(
            Map<TeamId, TimingTimestamp> starts) {
        this.starts =
                new LinkedHashMap<TeamId, TimingTimestamp>(starts);
    }

    public StageStartTimes copy() {
        return new StageStartTimes(starts);
    }

    public TimingTimestamp startFor(
            TeamId teamId) {
        if (teamId == null) {
            throw new IllegalArgumentException(
                    "teamId must not be null");
        }
        return starts.get(teamId);
    }

    public void set(
            TeamId teamId,
            TimingTimestamp start) {
        if (teamId == null) {
            throw new IllegalArgumentException(
                    "teamId must not be null");
        }
        if (start == null) {
            throw new IllegalArgumentException(
                    "start must not be null");
        }
        starts.put(
                teamId,
                start);
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
}
