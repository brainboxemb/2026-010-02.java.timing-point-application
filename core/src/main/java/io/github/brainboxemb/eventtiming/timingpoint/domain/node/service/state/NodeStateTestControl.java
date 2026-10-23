package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.state;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

/**
 * Engineering/test mutation boundary for one TimingNode's current state.
 *
 * <p>This contract is separate from {@link NodeStateAccessor} so normal state consumers
 * cannot acquire mutation operations through the read-only accessor.</p>
 */
public interface NodeStateTestControl {

    /**
     * Overrides one RegistrationId's stage start without changing upstream protocol state.
     *
     * <p>The value is an absolute UTC epoch-millisecond timestamp. A later ordered upstream
     * RaceData update may replace this engineering override.</p>
     */
    void overrideStageStart(
            RegistrationId registrationId,
            long startMillis);
}
