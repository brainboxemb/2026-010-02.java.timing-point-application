package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ConfigurationChange;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Owns scheduled duplicate-window housekeeping for TagProcessor. */
final class HousekeepingJob {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final ScheduledExecutorService scheduler;
    private final RegistrationDuplicateFilter duplicateFilter;

    private boolean started;
    private ScheduledFuture<?> schedule;

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

    synchronized void start() {
        started = true;
    }

    synchronized void stop() {
        started = false;
        cancel();
    }

    boolean isDuplicate(RegistrationId registrationId) {
        boolean duplicate = duplicateFilter.isDuplicate(registrationId);
        updateSchedule();
        return duplicate;
    }

    void recordRegistration(RegistrationId registrationId) {
        duplicateFilter.recordRegistration(registrationId);
        updateSchedule();
    }

    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");
        if (isStarted()) {
            scheduler.execute(
                    () -> {
                        if (isStarted()) {
                            duplicateFilter.onPolicyChanged();
                            if (change.previousValue().sweepCadenceNanos()
                                    != change.currentValue().sweepCadenceNanos()) {
                                restartSchedule();
                            } else {
                                updateSchedule();
                            }
                        }
                    });
        }
    }

    private void run() {
        if (isStarted()) {
            duplicateFilter.periodic();
            updateSchedule();
        }
    }

    private void updateSchedule() {
        if (!duplicateFilter.hasPendingState()) {
            synchronized (this) {
                cancel();
            }
            return;
        }

        synchronized (this) {
            if (!started) {
                return;
            }
            if (schedule != null && schedule.isDone()) {
                schedule = null;
            }
            if (schedule == null) {
                long cadenceNanos =
                        policyConfiguration.currentValue().sweepCadenceNanos();
                schedule =
                        scheduler.scheduleWithFixedDelay(
                                this::run,
                                cadenceNanos,
                                cadenceNanos,
                                TimeUnit.NANOSECONDS);
            }
        }
    }

    private void restartSchedule() {
        synchronized (this) {
            cancel();
        }
        updateSchedule();
    }

    private synchronized boolean isStarted() {
        return started;
    }

    private void cancel() {
        ScheduledFuture<?> current = schedule;
        schedule = null;
        if (current != null) {
            current.cancel(false);
        }
    }
}
