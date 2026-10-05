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
 * Passive passage state owned and called only by the TagProcessor execution lane.
 */
final class TagObservationFilter {
    private static final Logger LOG =
            LoggerFactory.getLogger(TagObservationFilter.class);

    private final TagProcessingPolicy policy;
    private final MonotonicClock monotonicClock;
    private final TagProcessingCounters counters;
    private final BiConsumer<RegistrationId, TimingTimestamp>
            validObservationCallback;

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
        BurstState state = bursts.get(registrationId);

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

        sendValidObservation(closedBurst);
    }

    void periodic() {
        List<ClosedBurst> closedBursts = new ArrayList<>();
        long now = monotonicClock.nowNanos();

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

        for (ClosedBurst closedBurst : closedBursts) {
            sendValidObservation(closedBurst);
        }
    }

    /**
     * Discards any currently aggregated next passage for one RegistrationId.
     *
     * <p>This is used when closing a previous burst caused TimingNode to accept
     * that registration. The observation that triggered closure may already
     * have started the next burst inside add(...); once the duplicate window
     * starts, retaining that burst would be wasted work.</p>
     */
    boolean discard(RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "registrationId must not be null");
        }
        return bursts.remove(registrationId) != null;
    }

    boolean hasPendingState() {
        return !bursts.isEmpty();
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
            if (observation.rssi() > maxRssi) {
                maxRssi = observation.rssi();
                maxRssiObservedAt = observation.observedAt();
            }
        }

        private boolean expired(long now, TagProcessingPolicy policy) {
            return now - lastSeenNanos >= policy.quietTimeoutNanos()
                    || now - firstSeenNanos >= policy.maxBurstDurationNanos();
        }

        private TimingTimestamp maxRssiObservedAt() {
            return maxRssiObservedAt;
        }
    }
}
