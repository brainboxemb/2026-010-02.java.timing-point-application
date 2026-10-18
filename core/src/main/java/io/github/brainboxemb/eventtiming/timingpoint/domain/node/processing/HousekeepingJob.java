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
 * Runs duplicate-window housekeeping while TagProcessor is active.
 *
 * <p>Duplicate checks, registration updates and housekeeping all execute on the
 * same TagProcessor scheduler thread.</p>
 */
final class HousekeepingJob {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final ScheduledExecutorService scheduler;
    private final RegistrationDuplicateFilter duplicateFilter;

    private ScheduledFuture<?> schedule;

    /** Creates duplicate-window state and stores the scheduler used for housekeeping. */
    HousekeepingJob(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            ScheduledExecutorService scheduler) {

        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");

        this.policyConfiguration = policyConfiguration;
        this.scheduler = scheduler;
        this.duplicateFilter = new RegistrationDuplicateFilter(policyConfiguration, monotonicClock);
    }

    /** Starts periodic duplicate cleanup and returns immediately. */
    synchronized void start() {
        if (schedule == null) {
            long cadenceNanos = policyConfiguration.currentValue().sweepCadenceNanos();
            schedule = scheduler.scheduleWithFixedDelay(
                    this::run,
                    cadenceNanos,
                    cadenceNanos,
                    TimeUnit.NANOSECONDS);
        }
    }

    /** Stops future housekeeping; a sweep already running may finish. */
    synchronized void stop() {
        if (schedule != null) {
            schedule.cancel(false);
            schedule = null;
        }
    }

    /** Returns whether the registration is still inside its duplicate window. */
    boolean isDuplicate(RegistrationId registrationId) {
        return duplicateFilter.isDuplicate(registrationId);
    }

    /** Starts or refreshes the duplicate window for one selected registration. */
    void recordRegistration(RegistrationId registrationId) {
        duplicateFilter.recordRegistration(registrationId);
    }

    /** Queues policy reconciliation on the TagProcessor scheduler and returns immediately. */
    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");
        scheduler.execute(this::applyPolicyChange);
    }

    /** Removes duplicate entries whose configured window has expired. */
    private void run() {
        duplicateFilter.periodic();
    }

    /** Reconciles duplicate state and replaces the active schedule with the new cadence. */
    private synchronized void applyPolicyChange() {
        if (schedule == null) {
            return;
        }

        duplicateFilter.onPolicyChanged();

        schedule.cancel(false);
        long cadenceNanos = policyConfiguration.currentValue().sweepCadenceNanos();
        schedule = scheduler.scheduleWithFixedDelay(
                this::run,
                cadenceNanos,
                cadenceNanos,
                TimeUnit.NANOSECONDS);
    }
}
