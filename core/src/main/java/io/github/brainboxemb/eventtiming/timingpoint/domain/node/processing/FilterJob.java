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

/** Owns scheduled passage-filter sweeps for TagProcessor. */
final class FilterJob {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final ScheduledExecutorService scheduler;
    private final TagObservationFilter filter;

    private volatile List<TagPassageSnapshot> passageSnapshots =
            Collections.emptyList();
    private boolean started;
    private ScheduledFuture<?> schedule;

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

    synchronized void start() {
        started = true;
    }

    synchronized void stop() {
        started = false;
        cancel();
    }

    void add(
            RegistrationId registrationId,
            TagObservation observation) {

        filter.add(registrationId, observation);
        publishPassageSnapshots();
        updateSchedule();
    }

    void discard(RegistrationId registrationId) {
        filter.discard(registrationId);
        publishPassageSnapshots();
        updateSchedule();
    }

    List<TagPassageSnapshot> passageSnapshots() {
        return passageSnapshots;
    }

    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");
        if (isStarted()) {
            scheduler.execute(
                    () -> {
                        if (isStarted()) {
                            filter.periodic();
                            publishPassageSnapshots();
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
            filter.periodic();
            publishPassageSnapshots();
            updateSchedule();
        }
    }

    private void updateSchedule() {
        if (!filter.hasPendingState()) {
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

    private void publishPassageSnapshots() {
        List<TagPassageSnapshot> current = filter.snapshots();
        passageSnapshots =
                current.isEmpty()
                        ? Collections.<TagPassageSnapshot>emptyList()
                        : Collections.unmodifiableList(
                                new ArrayList<TagPassageSnapshot>(current));
    }
}
