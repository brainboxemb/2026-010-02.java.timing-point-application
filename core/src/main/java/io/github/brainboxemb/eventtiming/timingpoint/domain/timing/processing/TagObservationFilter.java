package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Groups repeated reads for one RegistrationId and returns one selected
 * observation time per passage.
 *
 * <p>This class owns only passage detection and strongest-RSSI selection. It
 * knows nothing about tag decryption/mapping, TimingNode admission or runtime
 * scheduling.</p>
 */
final class TagObservationFilter {
    private static final Logger LOG =
            LoggerFactory.getLogger(TagObservationFilter.class);

    private final TagProcessingPolicy policy;
    private final MonotonicClock monotonicClock;
    private final TagProcessingCounters counters;
    private final BiConsumer<RegistrationId, TimingTimestamp>
            validObservationCallback;
    private final Object lock = new Object();

    /*
     * Default HashMap sizing is intentional. We do not yet have an
     * evidence-backed expected number of simultaneously active RegistrationIds.
     * An explicit capacity is useful only when a profile bound or Step-5
     * measurement gives a defensible expected count.
     */
    private final Map<RegistrationId, BurstState> bursts = new HashMap<>();

    TagObservationFilter(
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingCounters counters,
            BiConsumer<RegistrationId, TimingTimestamp>
                    validObservationCallback) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        if (counters == null) {
            throw new IllegalArgumentException("counters must not be null");
        }
        if (validObservationCallback == null) {
            throw new IllegalArgumentException(
                    "validObservationCallback must not be null");
        }

        this.policy = policy;
        this.monotonicClock = monotonicClock;
        this.counters = counters;
        this.validObservationCallback = validObservationCallback;
    }

    /**
     * Adds one mapped observation to the current passage for its RegistrationId.
     *
     * <p>This is deliberately not an event-handler API. TagProcessor owns the
     * Antenna EventSource callback and performs tag mapping before this filter.</p>
     */
    void add(
            RegistrationId registrationId,
            TagObservation observation) {
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "registrationId must not be null");
        }
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }

        ClosedBurst closedBurst = null;
        long now = monotonicClock.nowNanos();

        synchronized (lock) {
            BurstState state = bursts.get(registrationId);

            /*
             * A periodic expiry call normally closes old passages. This check
             * also handles delayed scheduling: a new read must never be merged
             * into a passage whose quiet/max deadline already passed.
             */
            if (state != null && state.expired(now, policy)) {
                bursts.remove(registrationId);
                closedBurst = new ClosedBurst(
                        registrationId,
                        state.maxRssiObservedAt());
                counters.recordClosedBurst();
                state = null;
            }

            if (state == null) {
                bursts.put(
                        registrationId,
                        new BurstState(observation, now));
            } else {
                state.add(observation, now);
            }
        }

        sendValidObservation(closedBurst);
    }

    /**
     * Performs periodic passage housekeeping.
     *
     * <p>TagProcessor decides when this component receives a periodic pass. The
     * filter itself has no knowledge of threads, schedulers or lifecycle.</p>
     */
    void periodic() {
        List<ClosedBurst> closedBursts = new ArrayList<>();
        long now = monotonicClock.nowNanos();

        synchronized (lock) {
            Iterator<Map.Entry<RegistrationId, BurstState>> iterator =
                    bursts.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<RegistrationId, BurstState> entry = iterator.next();
                BurstState state = entry.getValue();
                if (state.expired(now, policy)) {
                    iterator.remove();
                    closedBursts.add(new ClosedBurst(
                            entry.getKey(),
                            state.maxRssiObservedAt()));
                    counters.recordClosedBurst();
                }
            }
        }

        for (ClosedBurst closedBurst : closedBursts) {
            sendValidObservation(closedBurst);
        }
    }

    private void sendValidObservation(ClosedBurst closedBurst) {
        if (closedBurst == null) {
            return;
        }
        try {
            validObservationCallback.accept(
                    closedBurst.registrationId,
                    closedBurst.observedAt);
        } catch (RuntimeException ex) {
            /*
             * One failed admission attempt must not corrupt filter state or
             * prevent later RFID passages from being processed.
             */
            LOG.warn(
                    "Could not process valid observation for {}",
                    closedBurst.registrationId.value(),
                    ex);
        }
    }

    private static final class ClosedBurst {
        private final RegistrationId registrationId;
        private final TimingTimestamp observedAt;

        private ClosedBurst(
                RegistrationId registrationId,
                TimingTimestamp observedAt) {
            this.registrationId = registrationId;
            this.observedAt = observedAt;
        }
    }

    private static final class BurstState {
        private final long firstSeenNanos;
        private long lastSeenNanos;
        private int maxRssi;
        private TimingTimestamp maxRssiObservedAt;

        private BurstState(TagObservation observation, long now) {
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

        private TimingTimestamp maxRssiObservedAt() {
            return maxRssiObservedAt;
        }
    }
}
