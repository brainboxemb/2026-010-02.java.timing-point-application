package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Active TeamId-to-stage-start data for one TimingNode.
 *
 * <p>RACE_DATA ingress resolves protocol time values before they enter this state.
 * Every retained start is therefore one absolute UTC epoch-millisecond value.</p>
 */
public final class StageStartTimes {
    private final Map<TeamId, Long> starts;

    /** Creates empty live stage-start state. */
    public StageStartTimes() {

        this.starts = new LinkedHashMap<TeamId, Long>();
    }

    /** Creates an independent mutable copy from existing absolute start values. */
    private StageStartTimes(Map<TeamId, Long> starts) {

        this.starts = new LinkedHashMap<TeamId, Long>(starts);
    }

    /** Returns an independent mutable copy for transactional validation. */
    public StageStartTimes copy() {

        return new StageStartTimes(starts);
    }

    /** Returns the absolute UTC epoch-millisecond stage start, or null when none is configured. */
    public Long startMillisFor(TeamId teamId) {

        failIfInvalidArgument(teamId != null, "teamId must not be null");
        return starts.get(teamId);
    }

    /** Sets one already-normalized absolute UTC stage start. */
    public void set(TeamId teamId, long startMillis) {

        failIfInvalidArgument(teamId != null, "teamId must not be null");
        starts.put(teamId, startMillis);
    }

    /** Removes any current stage start for the TeamId. */
    public void remove(TeamId teamId) {

        failIfInvalidArgument(teamId != null, "teamId must not be null");
        starts.remove(teamId);
    }

    /** Replaces live state with a transactional stage-start copy. */
    public void replaceWith(StageStartTimes updatedStartTimes) {

        failIfInvalidArgument(updatedStartTimes != null, "updatedStartTimes must not be null");
        starts.clear();
        starts.putAll(updatedStartTimes.starts);
    }
}
