package oss.relay.eventtiming.timingpoint.domain.node.racedata;

import oss.relay.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Package-private RegistrationId-to-stage-start state owned by RaceDataState.
 *
 * <p>Values use compact UTC longs: time-only HHmmsscc remains below 100000000, while dated
 * values use yyyyMMddHHmmsscc. Copies participate in the same all-or-nothing RACE_DATA update
 * as team assignments.</p>
 */
final class StageStartTimes {
    private final Map<RegistrationId, Long> starts;

    /** Creates empty live stage-start state. */
    StageStartTimes() {

        this.starts = new LinkedHashMap<RegistrationId, Long>();
    }

    /** Creates an independent mutable copy from existing compact UTC start values. */
    private StageStartTimes(Map<RegistrationId, Long> starts) {

        this.starts = new LinkedHashMap<RegistrationId, Long>(starts);
    }

    /** Returns an independent mutable copy for transactional validation. */
    StageStartTimes copy() {

        return new StageStartTimes(starts);
    }

    /** Sets one already-normalized compact UTC stage start. */
    void set(RegistrationId registrationId, long stageStart) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        starts.put(registrationId, stageStart);
    }

    /** Returns the current normalized start value, or null when none is known. */
    Long startFor(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return starts.get(registrationId);
    }

    /** Visits all retained compact UTC stage starts in retained order and returns their count. */
    int visit(BiConsumer<RegistrationId, Long> visitor) {
        failIfInvalidArgument(visitor != null, "visitor must not be null");
        for (Map.Entry<RegistrationId, Long> entry : starts.entrySet()) {
            visitor.accept(entry.getKey(), entry.getValue());
        }
        return starts.size();
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
