package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Groups repeated reads for one TagId and emits one selected observation.
 *
 * <p>This class owns only passage detection and strongest-RSSI selection. It
 * knows nothing about RegistrationId mapping or TimingNode admission.</p>
 */
final class TagObservationFilter implements AutoCloseable {
    private static final Logger LOG =
            LoggerFactory.getLogger(TagObservationFilter.class);

    private final TagProcessingPolicy policy;
    private final MonotonicClock monotonicClock;
    private final TagProcessingCounters counters;
    private final Consumer<TagObservation> selectedObservationConsumer;
    private final Object lock = new Object();
    private final Map<TagId, BurstState> bursts = new HashMap<>();
    private final ScheduledFuture<?> expiryTask;

    private boolean closed;

    TagObservationFilter(
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            ScheduledExecutorService scheduler,
            TagProcessingCounters counters,
            Consumer<TagObservation> selectedObservationConsumer) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler must not be null");
        }
        if (counters == null) {
            throw new IllegalArgumentException("counters must not be null");
        }
        if (selectedObservationConsumer == null) {
            throw new IllegalArgumentException(
                    "selectedObservationConsumer must not be null");
        }

        this.policy = policy;
        this.monotonicClock = monotonicClock;
        this.counters = counters;
        this.selectedObservationConsumer = selectedObservationConsumer;

        /*
         * One periodic sweep per filter keeps scheduling work bounded. Creating a
         * ScheduledFuture for every RFID read would make scheduler state grow with
         * the observation rate and is unnecessary: only the per-TagId deadlines in
         * BurstState matter.
         */
        expiryTask = scheduler.scheduleWithFixedDelay(
                this::runExpirySweep,
                policy.sweepCadenceNanos(),
                policy.sweepCadenceNanos(),
                TimeUnit.NANOSECONDS);
    }

    /** Adds one decoded antenna observation to its TagId passage. */
    void onObservation(TagObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }

        TagObservation selected = null;
        long now = monotonicClock.nowNanos();

        synchronized (lock) {
            requireOpen();
            counters.recordObservation();

            BurstState state = bursts.get(observation.tagId());

            /*
             * The periodic sweep normally closes expired passages. This check also
             * handles a delayed scheduler: a new read must never be merged into a
             * passage whose quiet/max deadline already passed.
             */
            if (state != null && state.expired(now, policy)) {
                bursts.remove(observation.tagId());
                selected = state.selectedObservation();
                counters.recordClosedBurst();
                state = null;
            }

            if (state == null) {
                bursts.put(
                        observation.tagId(),
                        new BurstState(observation, now));
            } else {
                state.add(observation, now);
            }
        }

        publishSelected(selected);
    }

    /**
     * Closes every passage whose quiet or maximum duration has expired.
     *
     * <p>Package-private for deterministic filter tests. Runtime execution is
     * driven by the one periodic scheduler task created in the constructor.</p>
     */
    void expireBursts() {
        List<TagObservation> selected = new ArrayList<>();
        long now = monotonicClock.nowNanos();

        synchronized (lock) {
            if (closed) {
                return;
            }

            Iterator<Map.Entry<TagId, BurstState>> iterator =
                    bursts.entrySet().iterator();
            while (iterator.hasNext()) {
                BurstState state = iterator.next().getValue();
                if (state.expired(now, policy)) {
                    iterator.remove();
                    selected.add(state.selectedObservation());
                    counters.recordClosedBurst();
                }
            }
        }

        for (TagObservation observation : selected) {
            publishSelected(observation);
        }
    }

    private void runExpirySweep() {
        try {
            expireBursts();
        } catch (RuntimeException ex) {
            // A scheduler task that escapes with an exception stops future sweeps.
            LOG.warn("Tag observation expiry sweep failed", ex);
        }
    }

    private void publishSelected(TagObservation observation) {
        if (observation == null) {
            return;
        }
        try {
            selectedObservationConsumer.accept(observation);
        } catch (RuntimeException ex) {
            /*
             * Filtering must keep accepting later RFID reads even when mapping or
             * admission of one selected passage fails unexpectedly.
             */
            LOG.warn(
                    "Could not process selected observation for {}",
                    observation.tagId().value(),
                    ex);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            bursts.clear();
        }
        expiryTask.cancel(false);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("TagObservationFilter is closed");
        }
    }

    private static final class BurstState {
        private final TagId tagId;
        private final long firstSeenNanos;
        private long lastSeenNanos;
        private int maxRssi;
        private TimingTimestamp maxRssiObservedAt;

        private BurstState(TagObservation observation, long now) {
            tagId = observation.tagId();
            firstSeenNanos = now;
            lastSeenNanos = now;
            maxRssi = observation.rssi();
            maxRssiObservedAt = observation.observedAt();
        }

        private void add(TagObservation observation, long now) {
            lastSeenNanos = now;

            /*
             * Strictly greater is intentional. When two reads have the same best
             * RSSI, the earlier observation remains selected, so filtering does
             * not move the registration time forward without stronger evidence.
             */
            if (observation.rssi() > maxRssi) {
                maxRssi = observation.rssi();
                maxRssiObservedAt = observation.observedAt();
            }
        }

        private boolean expired(long now, TagProcessingPolicy policy) {
            /*
             * Observation timestamps may be corrected event time. Passage
             * deadlines describe elapsed runtime time and therefore use only the
             * monotonic clock values captured when reads arrive.
             */
            return now - lastSeenNanos >= policy.quietTimeoutNanos()
                    || now - firstSeenNanos >= policy.maxBurstDurationNanos();
        }

        private TagObservation selectedObservation() {
            return new TagObservation(
                    tagId,
                    maxRssi,
                    maxRssiObservedAt);
        }
    }
}
