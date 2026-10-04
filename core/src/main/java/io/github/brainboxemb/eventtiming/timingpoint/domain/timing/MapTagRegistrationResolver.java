package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Small local TagId-to-RegistrationId reference map used by the Step-5 slice.
 *
 * <p>This is not backoffice RaceData synchronisation. A later RaceData-backed
 * resolver can implement the same narrow boundary.</p>
 */
public final class MapTagRegistrationResolver implements TagRegistrationResolver {
    private final Map<TagId, RegistrationId> registrations;

    public MapTagRegistrationResolver(Map<TagId, RegistrationId> registrations) {
        if (registrations == null) {
            throw new IllegalArgumentException("registrations must not be null");
        }
        Map<TagId, RegistrationId> copy = new HashMap<>();
        for (Map.Entry<TagId, RegistrationId> entry : registrations.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "registrations must not contain null keys or values");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        this.registrations = Collections.unmodifiableMap(copy);
    }

    @Override
    public RegistrationId resolve(TagId tagId) {
        if (tagId == null) {
            throw new IllegalArgumentException("tagId must not be null");
        }
        return registrations.get(tagId);
    }
}
