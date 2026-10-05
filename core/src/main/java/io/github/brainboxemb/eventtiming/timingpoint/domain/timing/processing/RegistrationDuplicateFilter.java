package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

/** Passive duplicate-window state owned by the TagProcessor execution lane. */
final class RegistrationDuplicateFilter {
    enum Result {
        DUPLICATE,
        ACCEPTED,
        FULL,
        NOT_RUNNING
    }

    private final long duplicateWindowNanos;
    private final MonotonicClock monotonicClock;
    private final Map<RegistrationId, Long> acceptedRegistrations =
            new HashMap<>();

    private boolean cleanupStarted;
    private long lastCleanupNanos;

    RegistrationDuplicateFilter(
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        duplicateWindowNanos = policy.duplicateWindowNanos();
        this.monotonicClock = monotonicClock;
    }

    Result submitIfNew(
            RegistrationId registrationId,
            Supplier<CommandAdmission> submission) {
        if (registrationId == null) {
            throw new IllegalArgumentException("registrationId must not be null");
        }
        if (submission == null) {
            throw new IllegalArgumentException("submission must not be null");
        }

        long now = monotonicClock.nowNanos();

        if (duplicateWindowNanos > 0L) {
            Long acceptedAt = acceptedRegistrations.get(registrationId);
            if (acceptedAt != null) {
                if (now - acceptedAt < duplicateWindowNanos) {
                    return Result.DUPLICATE;
                }
                acceptedRegistrations.remove(registrationId);
            }
        }

        CommandAdmission admission = submission.get();
        if (admission == CommandAdmission.ACCEPTED
                && duplicateWindowNanos > 0L) {
            acceptedRegistrations.put(
                    registrationId,
                    monotonicClock.nowNanos());
        }
        return resultFor(admission);
    }

    void periodic() {
        removeOldEntriesWhenDue(monotonicClock.nowNanos());
    }

    boolean hasPendingState() {
        return !acceptedRegistrations.isEmpty();
    }

    private void removeOldEntriesWhenDue(long now) {
        if (duplicateWindowNanos == 0L) {
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

    private static Result resultFor(CommandAdmission admission) {
        switch (admission) {
            case ACCEPTED:
                return Result.ACCEPTED;
            case FULL:
                return Result.FULL;
            case NOT_RUNNING:
                return Result.NOT_RUNNING;
            default:
                throw new IllegalStateException(
                        "Unsupported TimingNode admission result " + admission);
        }
    }
}
