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

    synchronized void start() {
        if (active) {
            return;
        }

        active = true;
        scheduler.execute(this::resume);
    }

    synchronized void stop() {
        active = false;
        cancelScheduled();
    }

    boolean isDuplicate(RegistrationId registrationId) {
        return duplicateFilter.isDuplicate(registrationId);
    }

    void recordRegistration(RegistrationId registrationId) {
        duplicateFilter.recordRegistration(registrationId);
        scheduleNext();
    }

    /**
     * Applies duplicate-window policy changes on the scheduler thread that owns the table.
     *
     * <p>The configuration callback therefore never races with duplicate lookup
     * or expiry state.</p>
     */
    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");

        if (active) {
            scheduler.execute(this::applyPolicyChange);
        }
    }

    /**
     * Reconciles suppression state after activation.
     *
     * <p>Policy may have changed while the job was stopped, so expired state is
     * removed before deciding whether another cleanup timer is needed.</p>
     */
    private void resume() {
        if (!active) {
            return;
        }

        duplicateFilter.onPolicyChanged();
        scheduleNext();
    }

    /**
     * Runs one duplicate-window cleanup.
     *
     * <p>The scheduled reference is cleared first so this run can create one
     * successor only when suppression state still exists.</p>
     */
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

    /**
     * Re-evaluates retained registrations against a changed duplicate window.
     *
     * <p>The existing delayed run was planned with the old cadence/window, so
     * it is cancelled before state is reconciled and one current-policy run is
     * scheduled when necessary.</p>
     */
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

    /**
     * Keeps at most one duplicate cleanup timer outstanding.
     *
     * <p>An empty primitive table needs no periodic work. Recording a later
     * registration restarts housekeeping through this method.</p>
     */
    private synchronized void scheduleNext() {
        if (!active || scheduled != null || !duplicateFilter.hasPendingState()) {
            return;
        }

        long cadenceNanos = policyConfiguration.currentValue().sweepCadenceNanos();
        scheduled = scheduler.schedule(this::run, cadenceNanos, TimeUnit.NANOSECONDS);
    }

    private void cancelScheduled() {
        ScheduledFuture<?> current = scheduled;
        scheduled = null;

        if (current != null) {
            current.cancel(false);
        }
    }
}
