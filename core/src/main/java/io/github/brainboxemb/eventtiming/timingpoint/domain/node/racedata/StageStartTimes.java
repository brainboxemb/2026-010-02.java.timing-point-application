package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

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

    /** Creates empty live stage-start state. */
    public StageStartTimes() {
        this.starts =
                new LinkedHashMap<TeamId, TimingTimestamp>();
    }

    /** Creates an independent mutable copy from existing stage-start values. */
    private StageStartTimes(
            Map<TeamId, TimingTimestamp> starts) {
        this.starts =
                new LinkedHashMap<TeamId, TimingTimestamp>(starts);
    }

    /** Returns an independent mutable copy for transactional validation. */
    public StageStartTimes copy() {
        return new StageStartTimes(starts);
    }

    /** Returns the current stage start for the TeamId, or null when none is configured. */
    public TimingTimestamp startFor(
            TeamId teamId) {
        checkArgument(teamId != null, "teamId must not be null");
        return starts.get(teamId);
    }

    /** Sets the current stage start for one TeamId. */
    public void set(
            TeamId teamId,
            TimingTimestamp start) {
        checkArgument(teamId != null, "teamId must not be null");
        checkArgument(start != null, "start must not be null");
        starts.put(
                teamId,
                start);
    }

    /** Removes any current stage start for the TeamId. */
    public void remove(
            TeamId teamId) {
        checkArgument(teamId != null, "teamId must not be null");
        starts.remove(teamId);
    }

    /** Replaces live state with a transactional stage-start copy. */
    public void replaceWith(
            StageStartTimes updatedStartTimes) {
        checkArgument(updatedStartTimes != null, "updatedStartTimes must not be null");
        starts.clear();
        starts.putAll(updatedStartTimes.starts);
    }
}
