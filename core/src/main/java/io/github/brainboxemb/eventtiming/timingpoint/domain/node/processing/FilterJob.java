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
 * Runs passage-filter sweeps while TagProcessor has an open passage.
 *
 * <p>The observation lane and this job use the same single-thread scheduler.
 * Filter state is changed only on that scheduler thread.</p>
 */
final class FilterJob {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final ScheduledExecutorService scheduler;
    private final TagObservationFilter filter;

    private volatile List<TagPassageSnapshot> passageSnapshots = Collections.emptyList();
    private volatile boolean active;
    private ScheduledFuture<?> scheduled;

    /** Creates the filter and stores the scheduler used for delayed sweeps. */
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

    /** Enables the job and resumes a retained open passage without waiting for a sweep. */
    synchronized void start() {
        if (active) {
            return;
        }

        active = true;
        scheduler.execute(this::scheduleNext);
    }

    /** Disables the job and cancels its one pending delayed sweep. */
    synchronized void stop() {
        active = false;
        cancelScheduled();
    }

    /** Adds one mapped observation and schedules a sweep when passage state exists. */
    void add(RegistrationId registrationId, TagObservation observation) {
        filter.add(registrationId, observation);
        updateSnapshots();
        scheduleNext();
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

    /** Queues reconciliation with the current policy and returns immediately. */
    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");

        if (active) {
            scheduler.execute(this::applyPolicyChange);
        }
    }

    /** Runs one delayed sweep and schedules another only while a passage remains open. */
    private void run() {
        synchronized (this) {
            scheduled = null;
            if (!active) {
                return;
            }
        }

        filter.periodic();
        updateSnapshots();
        scheduleNext();
    }

    /** Applies the current policy and replans the one pending sweep. */
    private void applyPolicyChange() {
        synchronized (this) {
            if (!active) {
                return;
            }
            cancelScheduled();
        }

        filter.periodic();
        updateSnapshots();
        scheduleNext();
    }

    /** Schedules one future sweep when the job is active and passage state exists. */
    private synchronized void scheduleNext() {
        if (!active || scheduled != null || !filter.hasPendingState()) {
            return;
        }

        long cadenceNanos = policyConfiguration.currentValue().sweepCadenceNanos();
        scheduled = scheduler.schedule(this::run, cadenceNanos, TimeUnit.NANOSECONDS);
    }

    /** Cancels the one pending delayed sweep when one exists. */
    private void cancelScheduled() {
        ScheduledFuture<?> current = scheduled;
        scheduled = null;

        if (current != null) {
            current.cancel(false);
        }
    }

    /** Copies current filter state for lock-free engineering reads. */
    private void updateSnapshots() {
        List<TagPassageSnapshot> current = filter.snapshots();
        passageSnapshots = current.isEmpty()
                ? Collections.<TagPassageSnapshot>emptyList()
                : Collections.unmodifiableList(new ArrayList<TagPassageSnapshot>(current));
    }
}
