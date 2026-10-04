package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagId;

/**
 * Maps a decoded TagId to the RegistrationId stored in TimingData.
 *
 * <p>Returning {@code null} means the active mapping policy has no
 * RegistrationId for that tag. The implementation may be a deterministic
 * transformation or use local reference data when a deployment requires it.</p>
 */
@FunctionalInterface
public interface TagRegistrationMapper {
    RegistrationId map(TagId tagId);
}
