package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Passive accepted-registration duplicate window owned by the TagProcessor lane.
 *
 * <p>The filter only remembers registrations after TimingNode accepted their
 * fire-and-forget offer. Checking and recording are deliberately separate so TagProcessor
 * can suppress repeated observations before passage aggregation without
 * suppressing retries after FULL or NOT_RUNNING admission.</p>
 */
final class RegistrationDuplicateFilter {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final MonotonicClock monotonicClock;
    private final Map<RegistrationId, Long> acceptedRegistrations =
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
        if (policyConfiguration == null) {
            throw new IllegalArgumentException(
                    "policyConfiguration must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        this.policyConfiguration = policyConfiguration;
        this.monotonicClock = monotonicClock;
    }

    boolean isDuplicate(RegistrationId registrationId) {
        requireRegistrationId(registrationId);

        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();
        if (duplicateWindowNanos == 0L) {
            acceptedRegistrations.remove(registrationId);
            return false;
        }

        long now = monotonicClock.nowNanos();
        Long acceptedAt = acceptedRegistrations.get(registrationId);
        if (acceptedAt == null) {
            return false;
        }
        if (now - acceptedAt < duplicateWindowNanos) {
            return true;
        }

        acceptedRegistrations.remove(registrationId);
        return false;
    }

    void recordAccepted(RegistrationId registrationId) {
        requireRegistrationId(registrationId);
        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();
        if (duplicateWindowNanos == 0L) {
            return;
        }
        acceptedRegistrations.put(
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
            acceptedRegistrations.clear();
            cleanupStarted = false;
            lastCleanupNanos = 0L;
            return;
        }

        Iterator<Map.Entry<RegistrationId, Long>> iterator =
                acceptedRegistrations.entrySet().iterator();
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
                && !acceptedRegistrations.isEmpty();
    }

    private void removeOldEntriesWhenDue(long now) {
        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();
        if (duplicateWindowNanos == 0L) {
            acceptedRegistrations.clear();
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
                acceptedRegistrations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<RegistrationId, Long> entry = iterator.next();
            if (now - entry.getValue() >= duplicateWindowNanos) {
                iterator.remove();
            }
        }
        lastCleanupNanos = now;
    }

    private static void requireRegistrationId(RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException("registrationId must not be null");
        }
    }
}
