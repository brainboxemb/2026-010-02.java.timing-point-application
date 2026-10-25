package oss.relay.eventtiming.timingpoint.domain.node.processing;

import oss.relay.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.eventtiming.timingpoint.infra.configuration.ConfigurationChange;
import oss.relay.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import oss.relay.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.ObjLongConsumer;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Schedules passage-filter expiry sweeps only while passages are open.
 *
 * <p>Observation processing and delayed sweeps share the same single-thread
 * scheduler. {@link TagObservationFilter} therefore owns ordinary mutable
 * state without locks. This job only owns scheduling and publishes one cheap
 * volatile pending flag for external characterization reads; it does not build
 * or copy diagnostic passage snapshots.</p>
 */
final class FilterJob {
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final ScheduledExecutorService scheduler;
    private final TagObservationFilter filter;

    private volatile boolean pendingPassages;
    private volatile boolean active;
    private ScheduledFuture<?> scheduled;

    FilterJob(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            ScheduledExecutorService scheduler,
            ObjLongConsumer<RegistrationId> validObservationCallback) {

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

    void add(
            RegistrationId registrationId,
            TagObservation observation) {
        filter.add(
                registrationId,
                observation);
        updatePendingState();
        scheduleNext();
    }

    /** Discards an open passage after duplicate suppression starts. */
    void discard(RegistrationId registrationId) {
        filter.discard(registrationId);
        updatePendingState();
    }

    boolean hasPendingPassages() {
        return pendingPassages;
    }

    /**
     * Applies policy changes on the scheduler thread that owns filter state.
     *
     * <p>The configuration callback may run elsewhere, so it never mutates an
     * open passage directly.</p>
     */
    void policyChanged(ConfigurationChange<TagProcessingPolicy> change) {
        failIfInvalidArgument(change != null, "change must not be null");

        if (active) {
            scheduler.execute(this::applyPolicyChange);
        }
    }

    /**
     * Re-enters scheduling after activation without creating filter state.
     *
     * <p>If passages survived a lifecycle pause, only their pending/empty state
     * is republished and one delayed sweep is scheduled.</p>
     */
    private void resume() {
        updatePendingState();
        scheduleNext();
    }

    /**
     * Runs one delayed idle-flush sweep.
     *
     * <p>The scheduled reference is cleared before filtering so the same run
     * may schedule exactly one successor when passages remain open.</p>
     */
    private void run() {
        synchronized (this) {
            scheduled = null;
            if (!active) {
                return;
            }
        }

        filter.periodic();
        updatePendingState();
        scheduleNext();
    }

    /**
     * Applies new timing thresholds to already-open passages.
     *
     * <p>The previously planned delay used the old policy, so it is cancelled.
     * The filter is evaluated immediately and, if state remains, exactly one
     * new sweep is scheduled with the current cadence.</p>
     */
    private void applyPolicyChange() {
        synchronized (this) {
            if (!active) {
                return;
            }
            cancelScheduled();
        }

        filter.periodic();
        updatePendingState();
        scheduleNext();
    }

    /** Publishes the filter's current empty/non-empty state for external readers. */
    private void updatePendingState() {
        pendingPassages = filter.hasPendingState();
    }

    /**
     * Keeps at most one delayed passage sweep outstanding.
     *
     * <p>No timer is kept alive while the filter is empty. A new observation
     * re-enters this method and starts housekeeping again.</p>
     */
    private synchronized void scheduleNext() {
        if (!active || scheduled != null || !filter.hasPendingState()) {
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
