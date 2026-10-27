package oss.relay.timing.timingpoint.domain.node.racedata;

import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Package-private RegistrationId-to-stage-start state owned by RaceDataState.
 *
 * <p>Values use compact UTC longs: time-only HHmmsscc remains below 100000000, while dated
 * values use yyyyMMddHHmmsscc. NodeService validates each complete STAGE_STARTS message before
 * mutating retained state.</p>
 */
final class StageStartTimes {
    private final Map<RegistrationId, Long> starts;

    /** Creates empty live stage-start state. */
    StageStartTimes() {

        this.starts = new LinkedHashMap<RegistrationId, Long>();
    }

    /** Sets one already-normalized compact UTC stage start. */
    boolean set(RegistrationId registrationId, long stageStart) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        Long previous = starts.put(registrationId, stageStart);
        return previous == null || previous.longValue() != stageStart;
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
    boolean remove(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return starts.remove(registrationId) != null;
    }

}
