package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Package-private TeamId-to-stage-start state owned by RaceDataState.
 *
 * <p>Only normalized absolute UTC epoch milliseconds are retained. Copies participate in the
 * same all-or-nothing RACE_DATA update as team assignments; external code never receives the
 * mutable map or a partial civil-time value.</p>
 */
final class StageStartTimes {
    private final Map<TeamId, Long> starts;

    /** Creates empty live stage-start state. */
    StageStartTimes() {

        this.starts = new LinkedHashMap<TeamId, Long>();
    }

    /** Creates an independent mutable copy from existing absolute start values. */
    private StageStartTimes(Map<TeamId, Long> starts) {

        this.starts = new LinkedHashMap<TeamId, Long>(starts);
    }

    /** Returns an independent mutable copy for transactional validation. */
    StageStartTimes copy() {

        return new StageStartTimes(starts);
    }

    /** Sets one already-normalized absolute UTC stage start. */
    void set(TeamId teamId, long startMillis) {

        failIfInvalidArgument(teamId != null, "teamId must not be null");
        starts.put(teamId, startMillis);
    }

    /** Returns the current normalized start value, or null when none is known. */
    Long startMillisFor(TeamId teamId) {

        failIfInvalidArgument(teamId != null, "teamId must not be null");
        return starts.get(teamId);
    }

    /** Removes any current stage start for the TeamId. */
    void remove(TeamId teamId) {

        failIfInvalidArgument(teamId != null, "teamId must not be null");
        starts.remove(teamId);
    }

    /** Replaces live state with a transactional stage-start copy. */
    void replaceWith(StageStartTimes updatedStartTimes) {

        failIfInvalidArgument(updatedStartTimes != null, "updatedStartTimes must not be null");
        starts.clear();
        starts.putAll(updatedStartTimes.starts);
    }
}
