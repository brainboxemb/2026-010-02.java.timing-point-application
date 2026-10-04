package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

/**
 * Resolves a source TagId to the canonical RegistrationId used by TimingData.
 *
 * <p>Returning {@code null} means that the tag is not known in the currently
 * available local reference data. Obtaining/synchronising that reference data
 * belongs to later integration work.</p>
 */
@FunctionalInterface
public interface TagRegistrationResolver {
    RegistrationId resolve(TagId tagId);
}
