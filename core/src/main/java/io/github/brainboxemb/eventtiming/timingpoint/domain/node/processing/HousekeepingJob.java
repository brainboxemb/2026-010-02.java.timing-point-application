package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ConfigurationChange;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Schedules duplicate-window cleanup while remembered registrations exist. */
final class HousekeepingJob {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final ScheduledExecutorService scheduler;
    private final RegistrationDuplicateFilter duplicateFilter;

    private boolean started;
    private ScheduledFuture<?> scheduled;

    HousekeepingJob(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            ScheduledExecutorService scheduler) {

        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");

        this.policyConfiguration = policyConfiguration;
        this.scheduler = scheduler;
        duplicateFilter =
                new RegistrationDuplicateFilter(
                        policyConfiguration,
                        monotonicClock);
    }

    /** Enables housekeeping and reconciles retained state on the shared scheduler. */
    void start() {
        synchronized (this) {
            if (started) {
                return;
            }
            started = true;
        }
        scheduler.execute(this::resume);
    }

    /** Disables housekeeping and cancels the next pending cleanup. */
    synchronized void stop() {
        started = false;
        cancelScheduled();
    }

    /** Reports whether the registration is still inside its duplicate window. */
    boolean isDuplicate(RegistrationId registrationId) {
        boolean duplicate = duplicateFilter.isDuplicate(registrationId);
        updateSchedule();
        return duplicate;
    }

    /** Starts or refreshes one duplicate window and housekeeping when required. */
    void recordRegistration(RegistrationId registrationId) {
        duplicateFilter.recordRegistration(registrationId);
        updateSchedule();
    }

    /** Reconciles duplicate state and cadence on the shared scheduler. */
    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");
        if (isStarted()) {
            scheduler.execute(() -> applyPolicyChange(change));
        }
    }

    private void resume() {
        if (!isStarted()) {
            return;
        }

        duplicateFilter.onPolicyChanged();
        updateSchedule();
    }

    private void applyPolicyChange(ConfigurationChange<TagProcessingPolicy> change) {
        if (!isStarted()) {
            return;
        }

        duplicateFilter.onPolicyChanged();
        if (change.previousValue().sweepCadenceNanos()
                != change.currentValue().sweepCadenceNanos()) {
            synchronized (this) {
                cancelScheduled();
            }
        }
        updateSchedule();
    }

    /** Executes one cleanup and schedules another only while state remains. */
    private void runScheduled() {
        synchronized (this) {
            scheduled = null;
            if (!started) {
                return;
            }
        }

        duplicateFilter.periodic();
        updateSchedule();
    }

    private void updateSchedule() {
        if (!duplicateFilter.hasPendingState()) {
            synchronized (this) {
                cancelScheduled();
            }
            return;
        }

        synchronized (this) {
            if (!started || scheduled != null) {
                return;
            }

            long cadenceNanos =
                    policyConfiguration.currentValue().sweepCadenceNanos();
            scheduled =
                    scheduler.schedule(
                            this::runScheduled,
                            cadenceNanos,
                            TimeUnit.NANOSECONDS);
        }
    }

    private synchronized boolean isStarted() {
        return started;
    }

    private void cancelScheduled() {
        ScheduledFuture<?> current = scheduled;
        scheduled = null;
        if (current != null) {
            current.cancel(false);
        }
    }
}
