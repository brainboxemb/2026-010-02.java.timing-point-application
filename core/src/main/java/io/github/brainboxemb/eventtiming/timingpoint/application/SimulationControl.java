package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

/**
 * Narrow application boundary for engineering simulated-tag scenarios.
 *
 * <p>A scenario starts before the TagProcessor boundary. Implementations must
 * publish observations through the configured simulated antenna path rather
 * than committing registrations directly.</p>
 */
public interface SimulationControl {

    enum StartResult {
        ACCEPTED,
        UNKNOWN_REGISTRATION,
        UNKNOWN_PROFILE,
        UNAVAILABLE,
        BUSY
    }

    /**
     * Starts one simulated registration passage.
     *
     * @param registrationId resolved registration identity used to locate the
     *                       configured EventData tags
     * @param profileId built-in simulation profile identifier
     */
    StartResult startRegistration(
            RegistrationId registrationId,
            String profileId);
}
