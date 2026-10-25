package oss.relay.eventtiming.timingpoint.domain.node.processing;

import oss.relay.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import oss.relay.eventtiming.timingpoint.platform.environment.MonotonicClock;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Suppresses a RegistrationId for a configured elapsed-time window after selection.
 *
 * <p>The filter owns the duplicate rule only. {@link ActiveDuplicateRegistrations}
 * stores the currently suppressed registrations as packed primitive ids plus the
 * monotonic time at which each registration was selected.</p>
 *
 * <p>Duplicate state is a short-lived working set, not event history. Entries
 * expire when {@code now - acceptedAt >= duplicateWindow}; the exact boundary is
 * eligible again. Expired entries are removed both when looked up and by periodic
 * housekeeping. The storage size is therefore driven by registrations active
 * inside the duplicate window, not by observation queue capacity or total event
 * registrations.</p>
 *
 * <p>All calls are owned by the TagProcessor scheduler thread, so neither the
 * filter nor its active-state storage requires locking.</p>
 */
final class RegistrationDuplicateFilter {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final MonotonicClock monotonicClock;
    private final TagProcessingMetrics metrics;
    private final ActiveDuplicateRegistrations activeRegistrations =
            new ActiveDuplicateRegistrations();

    RegistrationDuplicateFilter(
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics) {
        this(ReadOnlyConfiguration.fixed(policy), monotonicClock, metrics);
    }

    RegistrationDuplicateFilter(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics) {
        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");
        failIfInvalidArgument(
                policyConfiguration.startupValue() != null,
                "policyConfiguration.startupValue must not be null");

        this.policyConfiguration = policyConfiguration;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
        recordStateMetrics();
    }

    /**
     * Reports whether a previously selected registration is still suppressed.
     *
     * <p>An expired entry is removed immediately. Only elapsed time strictly
     * smaller than the duplicate window is considered a duplicate.</p>
     */
    boolean isDuplicate(RegistrationId registrationId) {
        requireRegistrationId(registrationId);

        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();
        if (duplicateWindowNanos == 0L) {
            activeRegistrations.remove(registrationId.packedValue());
            recordStateMetrics();
            return false;
        }

        boolean duplicate =
                activeRegistrations.isDuplicate(
                        registrationId.packedValue(),
                        monotonicClock.nowNanos(),
                        duplicateWindowNanos);
        recordStateMetrics();
        return duplicate;
    }

    /**
     * Starts or refreshes suppression from the current monotonic time.
     *
     * <p>A zero duplicate window retains no state. Re-recording an active
     * RegistrationId refreshes its acceptance time instead of adding another
     * entry.</p>
     */
    void recordRegistration(RegistrationId registrationId) {
        requireRegistrationId(registrationId);

        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();
        if (duplicateWindowNanos == 0L) {
            activeRegistrations.remove(registrationId.packedValue());
        } else {
            activeRegistrations.record(
                    registrationId.packedValue(),
                    monotonicClock.nowNanos());
        }
        recordStateMetrics();
    }

    /** Removes registrations whose elapsed duplicate window has reached its boundary. */
    void periodic() {
        long duplicateWindowNanos =
                policyConfiguration.currentValue().duplicateWindowNanos();
        if (duplicateWindowNanos == 0L) {
            activeRegistrations.clear();
        } else {
            activeRegistrations.removeExpired(
                    monotonicClock.nowNanos(),
                    duplicateWindowNanos);
        }
        recordStateMetrics();
    }

    /**
     * Reconciles active registrations immediately when the duplicate-window policy changes.
     *
     * <p>Reducing the window may make retained entries immediately eligible;
     * disabling duplicate suppression clears all state.</p>
     */
    void onPolicyChanged() {
        periodic();
    }

    /** Reports whether one or more registrations are still retained for suppression. */
    boolean hasPendingState() {
        return !activeRegistrations.isEmpty();
    }

    /** Publishes compact active-state capacity and occupancy for characterization. */
    private void recordStateMetrics() {
        metrics.recordDuplicateState(
                ActiveDuplicateRegistrations.INITIAL_CAPACITY,
                activeRegistrations.capacity(),
                activeRegistrations.size(),
                activeRegistrations.peakSize());
    }

    private static void requireRegistrationId(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
    }
}
