package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Package-private RegistrationId-to-stage-start state owned by RaceDataState.
 *
 * <p>Only normalized absolute UTC epoch milliseconds are retained. Copies participate in the
 * same all-or-nothing RACE_DATA update as team assignments; external code never receives the
 * mutable map or a partial civil-time value.</p>
 */
final class StageStartTimes {
    private final Map<RegistrationId, Long> starts;

    /** Creates empty live stage-start state. */
    StageStartTimes() {

        this.starts = new LinkedHashMap<RegistrationId, Long>();
    }

    /** Creates an independent mutable copy from existing absolute start values. */
    private StageStartTimes(Map<RegistrationId, Long> starts) {

        this.starts = new LinkedHashMap<RegistrationId, Long>(starts);
    }

    /** Returns an independent mutable copy for transactional validation. */
    StageStartTimes copy() {

        return new StageStartTimes(starts);
    }

    /** Sets one already-normalized absolute UTC stage start. */
    void set(RegistrationId registrationId, long startMillis) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        starts.put(registrationId, startMillis);
    }

    /** Returns the current normalized start value, or null when none is known. */
    Long startMillisFor(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return starts.get(registrationId);
    }

    /** Removes any current stage start for the RegistrationId. */
    void remove(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        starts.remove(registrationId);
    }

    /** Replaces live state with a transactional stage-start copy. */
    void replaceWith(StageStartTimes updatedStartTimes) {

        failIfInvalidArgument(updatedStartTimes != null, "updatedStartTimes must not be null");
        starts.clear();
        starts.putAll(updatedStartTimes.starts);
    }
}
