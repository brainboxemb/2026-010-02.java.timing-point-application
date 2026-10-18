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

/** Schedules passage-filter sweeps while passage state exists. */
final class FilterJob {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final ScheduledExecutorService scheduler;
    private final TagObservationFilter filter;

    private volatile List<TagPassageSnapshot> passageSnapshots =
            Collections.emptyList();

    private boolean started;
    private ScheduledFuture<?> scheduled;

    FilterJob(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            ScheduledExecutorService scheduler,
            BiConsumer<RegistrationId, TimingTimestamp> validObservationCallback) {

        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");

        this.policyConfiguration = policyConfiguration;
        this.scheduler = scheduler;
        filter =
                new TagObservationFilter(
                        policyConfiguration,
                        monotonicClock,
                        metrics,
                        validObservationCallback);
    }

    /** Enables scheduling. Existing passage state resumes on the shared scheduler. */
    void start() {
        synchronized (this) {
            if (started) {
                return;
            }
            started = true;
        }
        scheduler.execute(this::resume);
    }

    /** Disables scheduling and cancels the next pending sweep. */
    synchronized void stop() {
        started = false;
        cancelScheduled();
    }

    /** Adds one mapped observation and starts timed filtering when required. */
    void add(
            RegistrationId registrationId,
            TagObservation observation) {

        filter.add(registrationId, observation);
        publishPassageSnapshots();
        updateSchedule();
    }

    /** Discards one open passage after duplicate suppression starts. */
    void discard(RegistrationId registrationId) {
        filter.discard(registrationId);
        publishPassageSnapshots();
        updateSchedule();
    }

    /** Latest immutable engineering snapshot of current passages. */
    List<TagPassageSnapshot> passageSnapshots() {
        return passageSnapshots;
    }

    /** Re-evaluates passage state and cadence on the shared scheduler. */
    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");
        if (isStarted()) {
            scheduler.execute(() -> applyPolicyChange(change));
        }
    }

    private void resume() {
        if (isStarted()) {
            updateSchedule();
        }
    }

    private void applyPolicyChange(ConfigurationChange<TagProcessingPolicy> change) {
        if (!isStarted()) {
            return;
        }

        if (change.previousValue().sweepCadenceNanos()
                != change.currentValue().sweepCadenceNanos()) {
            synchronized (this) {
                cancelScheduled();
            }
        }

        filter.periodic();
        publishPassageSnapshots();
        updateSchedule();
    }

    /** Executes one sweep and schedules another only while passage state remains. */
    private void runScheduled() {
        synchronized (this) {
            scheduled = null;
            if (!started) {
                return;
            }
        }

        filter.periodic();
        publishPassageSnapshots();
        updateSchedule();
    }

    private void updateSchedule() {
        if (!filter.hasPendingState()) {
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

    private void publishPassageSnapshots() {
        List<TagPassageSnapshot> current = filter.snapshots();
        passageSnapshots =
                current.isEmpty()
                        ? Collections.<TagPassageSnapshot>emptyList()
                        : Collections.unmodifiableList(
                                new ArrayList<TagPassageSnapshot>(current));
    }
}
