package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Suppresses recently accepted registrations by RegistrationId.
 *
 * <p>A duplicate window starts only after TimingNode queue admission succeeds.
 * FULL and NOT_RUNNING therefore remain retryable.</p>
 */
final class RegistrationDuplicateFilter {
    enum Result {
        DUPLICATE,
        ACCEPTED,
        FULL,
        NOT_RUNNING
    }

    private final long duplicateWindowNanos;
    private final MonotonicClock monotonicClock;
    /*
     * Default HashMap sizing is intentional. The useful initial capacity depends
     * on the number of distinct accepted RegistrationIds that can still be inside
     * the duplicate window. Use an explicit capacity only when a deployment bound
     * or Step-5 measurement gives a defensible expected count.
     */
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

    /**
     * Checks duplicate state and performs one non-blocking TimingNode submission.
     *
     * <p>The lock intentionally covers both the duplicate check and queue
     * admission. Two concurrent TagIds may map to the same RegistrationId; this
     * makes it impossible for both callers to observe "not duplicate" before the
     * first accepted submission is remembered. The supplied action must therefore
     * remain the normal bounded/non-blocking TimingNode.submit operation.</p>
     */
    synchronized Result submitIfNew(
            RegistrationId registrationId,
            Supplier<CommandAdmission> submission) {
        if (registrationId == null) {
            throw new IllegalArgumentException("registrationId must not be null");
        }
        if (submission == null) {
            throw new IllegalArgumentException("submission must not be null");
        }

        long now = monotonicClock.nowNanos();
        removeOldEntriesWhenDue(now);

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

        /*
         * Do not remember attempts that never entered the TimingNode queue. A
         * later RFID passage must be allowed to retry after FULL/NOT_RUNNING.
         */
        if (admission == CommandAdmission.ACCEPTED
                && duplicateWindowNanos > 0L) {
            acceptedRegistrations.put(
                    registrationId,
                    monotonicClock.nowNanos());
        }

        return resultFor(admission);
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

        /*
         * Scanning the complete map on every accepted registration would turn a
         * cheap duplicate check into O(n) hot-path work. A bounded periodic lazy
         * cleanup is enough; the current RegistrationId is still checked exactly
         * on every submission.
         */
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
