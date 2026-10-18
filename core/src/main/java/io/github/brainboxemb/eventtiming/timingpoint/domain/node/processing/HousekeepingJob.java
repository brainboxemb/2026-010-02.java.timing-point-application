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

    private volatile boolean started;
    private ScheduledFuture<?> schedule;

    /** Creates duplicate-window state and its scheduled housekeeping wrapper. */
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

    /** Starts one fixed-delay housekeeping schedule and returns immediately. */
    synchronized void start() {
        if (started) {
            return;
        }

        started = true;
        schedule = schedule();
    }

    /** Cancels future housekeeping; an already running sweep may finish. */
    synchronized void stop() {
        started = false;
        cancelSchedule();
    }

    /** Returns whether the registration is still inside its duplicate window. */
    boolean isDuplicate(RegistrationId registrationId) {
        return duplicateFilter.isDuplicate(registrationId);
    }

    /** Starts or refreshes the duplicate window for one selected registration. */
    void recordRegistration(RegistrationId registrationId) {
        duplicateFilter.recordRegistration(registrationId);
    }

    /** Queues application of a changed policy and returns immediately. */
    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");

        if (started) {
            scheduler.execute(this::applyPolicyChange);
        }
    }

    /** Removes duplicate entries whose configured window has expired. */
    private void run() {
        if (started) {
            duplicateFilter.periodic();
        }
    }

    /** Reconciles duplicate state and restarts the timer with the current cadence. */
    private void applyPolicyChange() {
        if (!started) {
            return;
        }

        duplicateFilter.onPolicyChanged();
        restartSchedule();
    }

    /** Replaces the current fixed-delay registration after a policy change. */
    private synchronized void restartSchedule() {
        if (!started) {
            return;
        }

        cancelSchedule();
        schedule = schedule();
    }

    /** Registers the next fixed-delay series using the current policy cadence. */
    private ScheduledFuture<?> schedule() {
        long cadenceNanos = policyConfiguration.currentValue().sweepCadenceNanos();
        return scheduler.scheduleWithFixedDelay(
                this::run,
                cadenceNanos,
                cadenceNanos,
                TimeUnit.NANOSECONDS);
    }

    /** Cancels the current JDK schedule when one exists. */
    private void cancelSchedule() {
        ScheduledFuture<?> current = schedule;
        schedule = null;

        if (current != null) {
            current.cancel(false);
        }
    }
}
