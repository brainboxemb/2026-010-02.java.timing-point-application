package oss.relay.timing.timingpoint.domain.node.service.state;

import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;

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
     * <p>The engineering boundary accepts an absolute UTC epoch-millisecond timestamp.
     * NodeState converts it once to the same compact UTC representation used by RaceData.
     * A later ordered upstream update may replace the override.</p>
     */
    void overrideStageStart(
            RegistrationId registrationId,
            long startMillis);
}
