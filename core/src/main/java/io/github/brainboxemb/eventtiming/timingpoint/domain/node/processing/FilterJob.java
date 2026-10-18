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
 * Runs the periodic passage filter while TagProcessor is active.
 *
 * <p>The observation lane and this job use the same single-thread scheduler.
 * Filter state therefore needs no internal locking.</p>
 */
final class FilterJob {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final ScheduledExecutorService scheduler;
    private final TagObservationFilter filter;

    private volatile List<TagPassageSnapshot> passageSnapshots = Collections.emptyList();
    private volatile boolean started;
    private ScheduledFuture<?> schedule;

    /** Creates the passage filter and its scheduled execution wrapper. */
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

    /** Starts one fixed-delay filter schedule and returns without waiting for a sweep. */
    synchronized void start() {
        if (started) {
            return;
        }

        started = true;
        schedule = schedule();
    }

    /** Cancels future sweeps; an already running sweep is allowed to finish. */
    synchronized void stop() {
        started = false;
        cancelSchedule();
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

    /** Returns the latest diagnostic snapshot without scheduling work. */
    List<TagPassageSnapshot> passageSnapshots() {
        return passageSnapshots;
    }

    /** Queues application of a changed policy and returns immediately. */
    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");

        if (started) {
            scheduler.execute(this::applyPolicyChange);
        }
    }

    /** Runs one periodic passage sweep on the shared scheduler thread. */
    private void run() {
        if (!started) {
            return;
        }

        filter.periodic();
        updateSnapshots();
    }

    /** Applies the current policy and restarts the timer with its current cadence. */
    private void applyPolicyChange() {
        if (!started) {
            return;
        }

        filter.periodic();
        updateSnapshots();
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

    /** Publishes an immutable copy of the current passage diagnostics. */
    private void updateSnapshots() {
        List<TagPassageSnapshot> current = filter.snapshots();
        passageSnapshots = current.isEmpty()
                ? Collections.<TagPassageSnapshot>emptyList()
                : Collections.unmodifiableList(new ArrayList<TagPassageSnapshot>(current));
    }
}
