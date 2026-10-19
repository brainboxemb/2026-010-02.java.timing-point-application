package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ConfigurationChange;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Schedules duplicate-window cleanup only while suppression state exists.
 *
 * <p>Registration checks and cleanup share the TagProcessor scheduler thread.
 * This job owns only delayed scheduling; RegistrationDuplicateFilter owns the
 * suppression rule and primitive state.</p>
 */
final class HousekeepingJob {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final ScheduledExecutorService scheduler;
    private final RegistrationDuplicateFilter duplicateFilter;

    private volatile boolean active;
    private ScheduledFuture<?> scheduled;

    /** Creates duplicate-window state and stores the scheduler used for delayed cleanup. */
    HousekeepingJob(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            ScheduledExecutorService scheduler) {

        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");

        this.policyConfiguration = policyConfiguration;
        this.scheduler = scheduler;
        this.duplicateFilter = new RegistrationDuplicateFilter(policyConfiguration, monotonicClock, metrics);
    }

    /** Enables the job and reconciles retained duplicate state on the scheduler thread. */
    synchronized void start() {
        if (active) {
            return;
        }

        active = true;
        scheduler.execute(this::resume);
    }

    /** Disables the job and cancels its one pending cleanup. */
    synchronized void stop() {
        active = false;
        cancelScheduled();
    }

    /** Returns whether the registration is still inside its duplicate window. */
    boolean isDuplicate(RegistrationId registrationId) {
        return duplicateFilter.isDuplicate(registrationId);
    }

    /** Starts or refreshes a duplicate window and schedules cleanup when needed. */
    void recordRegistration(RegistrationId registrationId) {
        duplicateFilter.recordRegistration(registrationId);
        scheduleNext();
    }

    /** Queues reconciliation with the current policy and returns immediately. */
    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");

        if (active) {
            scheduler.execute(this::applyPolicyChange);
        }
    }

    /** Reconciles retained duplicate state when the job becomes active. */
    private void resume() {
        if (!active) {
            return;
        }

        duplicateFilter.onPolicyChanged();
        scheduleNext();
    }

    /** Removes expired registrations and schedules another cleanup only when needed. */
    private void run() {
        synchronized (this) {
            scheduled = null;
            if (!active) {
                return;
            }
        }

        duplicateFilter.periodic();
        scheduleNext();
    }

    /** Applies the current policy and replans the one pending cleanup. */
    private void applyPolicyChange() {
        synchronized (this) {
            if (!active) {
                return;
            }
            cancelScheduled();
        }

        duplicateFilter.onPolicyChanged();
        scheduleNext();
    }

    /** Schedules one future cleanup when remembered duplicate state exists. */
    private synchronized void scheduleNext() {
        if (!active || scheduled != null || !duplicateFilter.hasPendingState()) {
            return;
        }

        long cadenceNanos = policyConfiguration.currentValue().sweepCadenceNanos();
        scheduled = scheduler.schedule(this::run, cadenceNanos, TimeUnit.NANOSECONDS);
    }

    /** Cancels the one pending cleanup when one exists. */
    private void cancelScheduled() {
        ScheduledFuture<?> current = scheduled;
        scheduled = null;

        if (current != null) {
            current.cancel(false);
        }
    }
}
