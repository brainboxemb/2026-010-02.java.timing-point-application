package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Passive selected-registration duplicate-window state. */
final class RegistrationDuplicateFilter {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final MonotonicClock monotonicClock;
    private final Map<RegistrationId, Long> registrations =
            new HashMap<RegistrationId, Long>();

    /** Creates a filter with a fixed processing policy. */
    RegistrationDuplicateFilter(
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock) {
        this(ReadOnlyConfiguration.fixed(policy), monotonicClock);
    }

    /** Creates a filter that follows the live TagProcessor policy configuration. */
    RegistrationDuplicateFilter(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock) {

        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        this.policyConfiguration = policyConfiguration;
        this.monotonicClock = monotonicClock;
    }

    /** Reports whether the registration is still inside its configured duplicate window. */
    boolean isDuplicate(RegistrationId registrationId) {
        requireRegistrationId(registrationId);

        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();
        Long acceptedAt = registrations.get(registrationId);

        if (duplicateWindowNanos == 0L || acceptedAt == null) {
            registrations.remove(registrationId);
            return false;
        }

        if (monotonicClock.nowNanos() - acceptedAt < duplicateWindowNanos) {
            return true;
        }

        registrations.remove(registrationId);
        return false;
    }

    /** Starts or refreshes the duplicate window after a registration was selected. */
    void recordRegistration(RegistrationId registrationId) {
        requireRegistrationId(registrationId);

        if (policyConfiguration.currentValue().duplicateWindowNanos() == 0L) {
            registrations.remove(registrationId);
        } else {
            registrations.put(
                    registrationId,
                    monotonicClock.nowNanos());
        }
    }

    /** Removes registrations whose duplicate window has expired. */
    void periodic() {
        removeExpired(monotonicClock.nowNanos());
    }

    /** Reconciles remembered duplicate state with the current policy. */
    void onPolicyChanged() {
        if (policyConfiguration.currentValue().duplicateWindowNanos() == 0L) {
            registrations.clear();
        } else {
            removeExpired(monotonicClock.nowNanos());
        }
    }

    /** Reports whether remembered registrations still require housekeeping. */
    boolean hasPendingState() {
        return policyConfiguration.currentValue().duplicateWindowNanos() != 0L
                && !registrations.isEmpty();
    }

    private void removeExpired(long now) {
        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();

        Iterator<Map.Entry<RegistrationId, Long>> iterator =
                registrations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RegistrationId, Long> entry = iterator.next();
            if (now - entry.getValue() >= duplicateWindowNanos) {
                iterator.remove();
            }
        }
    }

    private static void requireRegistrationId(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
    }
}
