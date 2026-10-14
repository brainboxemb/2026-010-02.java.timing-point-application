package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Passive selected-registration duplicate window owned by the TagProcessor lane.
 *
 * <p>The filter only remembers registrations after TagProcessor selected them as completed registrations. Checking and recording are separate so repeated tag observations can be
 * suppressed before passage aggregation.</p>
 */
final class RegistrationDuplicateFilter {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final MonotonicClock monotonicClock;
    private final Map<RegistrationId, Long> registrations =
            new HashMap<>();

    private boolean cleanupStarted;
    private long lastCleanupNanos;

    RegistrationDuplicateFilter(
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock) {
        this(ReadOnlyConfiguration.fixed(policy), monotonicClock);
    }

    RegistrationDuplicateFilter(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock) {
        checkArgument(
                policyConfiguration != null,
                "policyConfiguration must not be null");
        checkArgument(monotonicClock != null, "monotonicClock must not be null");
        this.policyConfiguration = policyConfiguration;
        this.monotonicClock = monotonicClock;
    }

    boolean isDuplicate(RegistrationId registrationId) {
        requireRegistrationId(registrationId);

        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();
        if (duplicateWindowNanos == 0L) {
            registrations.remove(registrationId);
            return false;
        }

        long now = monotonicClock.nowNanos();
        Long acceptedAt = registrations.get(registrationId);
        if (acceptedAt == null) {
            return false;
        }
        if (now - acceptedAt < duplicateWindowNanos) {
            return true;
        }

        registrations.remove(registrationId);
        return false;
    }

    void recordRegistration(RegistrationId registrationId) {
        requireRegistrationId(registrationId);
        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();
        if (duplicateWindowNanos == 0L) {
            return;
        }
        registrations.put(
                registrationId,
                monotonicClock.nowNanos());
    }

    void periodic() {
        removeOldEntriesWhenDue(monotonicClock.nowNanos());
    }

    void onPolicyChanged() {
        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();
        long now = monotonicClock.nowNanos();

        if (duplicateWindowNanos == 0L) {
            registrations.clear();
            cleanupStarted = false;
            lastCleanupNanos = 0L;
            return;
        }

        Iterator<Map.Entry<RegistrationId, Long>> iterator =
                registrations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RegistrationId, Long> entry = iterator.next();
            if (now - entry.getValue() >= duplicateWindowNanos) {
                iterator.remove();
            }
        }
        cleanupStarted = true;
        lastCleanupNanos = now;
    }

    boolean hasPendingState() {
        return policyConfiguration.currentValue().duplicateWindowNanos() != 0L
                && !registrations.isEmpty();
    }

    private void removeOldEntriesWhenDue(long now) {
        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();
        if (duplicateWindowNanos == 0L) {
            registrations.clear();
            cleanupStarted = false;
            lastCleanupNanos = 0L;
            return;
        }

        if (!cleanupStarted) {
            cleanupStarted = true;
            lastCleanupNanos = now;
            return;
        }

        if (now - lastCleanupNanos < duplicateWindowNanos) {
            return;
        }

        Iterator<Map.Entry<RegistrationId, Long>> iterator =
                registrations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RegistrationId, Long> entry = iterator.next();
            if (now - entry.getValue() >= duplicateWindowNanos) {
                iterator.remove();
            }
        }
        lastCleanupNanos = now;
    }

    private static void requireRegistrationId(RegistrationId registrationId) {
        checkArgument(registrationId != null, "registrationId must not be null");
    }
}
