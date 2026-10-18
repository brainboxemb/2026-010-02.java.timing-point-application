package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ConfigurationChange;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Runs passage-filter sweeps while TagProcessor is active.
 *
 * <p>The observation lane and this job use the same single-thread scheduler.
 * Filter state therefore needs no internal locking.</p>
 */
final class FilterJob {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final ScheduledExecutorService scheduler;
    private final TagObservationFilter filter;

    private volatile List<TagPassageSnapshot> passageSnapshots = Collections.emptyList();
    private ScheduledFuture<?> schedule;

    /** Creates the filter and stores the scheduler used for its periodic sweep. */
    FilterJob(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            ScheduledExecutorService scheduler,
            BiConsumer<RegistrationId, TimingTimestamp> validObservationCallback) {

        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");
        failIfInvalidArgument(validObservationCallback != null, "validObservationCallback must not be null");

        this.policyConfiguration = policyConfiguration;
        this.scheduler = scheduler;
        this.filter = new TagObservationFilter(
                policyConfiguration,
                monotonicClock,
                metrics,
                validObservationCallback);
    }

    /** Starts periodic filtering and returns immediately. */
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

    /** Stops future filter sweeps; a sweep already running may finish. */
    synchronized void stop() {
        if (schedule != null) {
            schedule.cancel(false);
            schedule = null;
        }
    }

    /** Adds one mapped observation on the shared TagProcessor scheduler thread. */
    void add(RegistrationId registrationId, TagObservation observation) {
        filter.add(registrationId, observation);
        updateSnapshots();
    }

    /** Discards an open passage after duplicate suppression starts. */
    void discard(RegistrationId registrationId) {
        filter.discard(registrationId);
        updateSnapshots();
    }

    /** Returns the latest passage diagnostics without scheduling work. */
    List<TagPassageSnapshot> passageSnapshots() {
        return passageSnapshots;
    }

    /** Queues policy reconciliation on the TagProcessor scheduler and returns immediately. */
    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");
        scheduler.execute(this::applyPolicyChange);
    }

    /** Runs one periodic filter sweep. */
    private void run() {
        filter.periodic();
        updateSnapshots();
    }

    /** Re-evaluates current passages and replaces the active schedule with the new cadence. */
    private synchronized void applyPolicyChange() {
        if (schedule == null) {
            return;
        }

        filter.periodic();
        updateSnapshots();

        schedule.cancel(false);
        long cadenceNanos = policyConfiguration.currentValue().sweepCadenceNanos();
        schedule = scheduler.scheduleWithFixedDelay(
                this::run,
                cadenceNanos,
                cadenceNanos,
                TimeUnit.NANOSECONDS);
    }

    /** Copies current filter state for lock-free engineering reads. */
    private void updateSnapshots() {
        List<TagPassageSnapshot> current = filter.snapshots();
        passageSnapshots = current.isEmpty()
                ? Collections.<TagPassageSnapshot>emptyList()
                : Collections.unmodifiableList(new ArrayList<TagPassageSnapshot>(current));
    }
}
