package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Compact derived view of currently active registration ADD records.
 *
 * <p>The authoritative append-only history remains in LogBook. This object keeps
 * only the active registration facts needed by derived timing calculations.
 * Lifecycle records are ignored and a REV removes one matching active ADD.</p>
 */
public final class ActiveRegistrations {
    private final List<TimingData> active = new ArrayList<TimingData>();

    /** Clears all derived active-registration state before startup reconstruction. */
    public void reset() {
        active.clear();
    }

    /** Applies one committed TimingData fact to the compact active-registration view. */
    public void apply(TimingData data) {
        checkArgument(data != null, "data must not be null");

        TimingData.RegistrationAction action = registrationAction(data);
        if (action == TimingData.RegistrationAction.ADD) {
            active.add(data);
        } else if (action == TimingData.RegistrationAction.REV) {
            removeMatchingRegistration(data);
        }
    }

    /**
     * Returns a read-only live view for same-lane derived calculations.
     *
     * <p>The view is not a history snapshot and must not escape the owning Task Lane.</p>
     */
    Iterable<TimingData> values() {
        return Collections.unmodifiableList(active);
    }

    /** Removes one active ADD matching the identity carried by a REV record. */
    private void removeMatchingRegistration(TimingData revoke) {
        RegistrationId revokeRegistrationId = registrationId(revoke);

        for (int index = active.size() - 1; index >= 0; index--) {
            TimingData activeRegistration = active.get(index);
            if (revokeRegistrationId.equals(registrationId(activeRegistration))
                    && Objects.equals(revoke.locationId(), activeRegistration.locationId())
                    && revoke.effectiveTime().equals(activeRegistration.effectiveTime())) {
                active.remove(index);
                break;
            }
        }
    }

    /** Returns the RegistrationId carried by an automatic or manual registration record. */
    private static RegistrationId registrationId(TimingData data) {
        if (data instanceof TimingData.AutomaticRegistration) {
            return ((TimingData.AutomaticRegistration) data).registrationId();
        }
        if (data instanceof TimingData.ManualRegistration) {
            return ((TimingData.ManualRegistration) data).registrationId();
        }
        throw new IllegalArgumentException("TimingData is not a registration");
    }

    /** Returns the registration action, or null when the TimingData record is not a registration. */
    private static TimingData.RegistrationAction registrationAction(TimingData data) {
        if (data instanceof TimingData.AutomaticRegistration) {
            return ((TimingData.AutomaticRegistration) data).action();
        }
        if (data instanceof TimingData.ManualRegistration) {
            return ((TimingData.ManualRegistration) data).action();
        }
        return null;
    }
}
