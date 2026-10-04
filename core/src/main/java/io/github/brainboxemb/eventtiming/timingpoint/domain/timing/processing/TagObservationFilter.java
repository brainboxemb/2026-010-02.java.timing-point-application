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
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Groups repeated reads for one TagId and returns one valid observation per passage.
 *
 * <p>This class owns only passage detection and strongest-RSSI selection. It
 * knows nothing about RegistrationId mapping, TimingNode admission or runtime
 * scheduling.</p>
 */
final class TagObservationFilter {
    private static final Logger LOG =
            LoggerFactory.getLogger(TagObservationFilter.class);

    private final TagProcessingPolicy policy;
    private final MonotonicClock monotonicClock;
    private final TagProcessingCounters counters;
    private final Consumer<TagObservation> validObservationCallback;
    private final Object lock = new Object();

    /*
     * Default HashMap sizing is intentional. We do not yet have an
     * evidence-backed expected number of simultaneously active TagIds. An
     * explicit capacity is useful only when a profile bound or Step-5
     * measurement gives a defensible expected count.
     */
    private final Map<TagId, BurstState> bursts = new HashMap<>();

    TagObservationFilter(
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingCounters counters,
            Consumer<TagObservation> validObservationCallback) {
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
     * Adds one decoded observation to the current passage for its TagId.
     *
     * <p>This is deliberately not an event-handler API. TagProcessor owns the
     * Antenna EventSource callback and feeds observations into this filter.</p>
     */
    void add(TagObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }

        TagObservation validObservation = null;
        long now = monotonicClock.nowNanos();

        synchronized (lock) {
            counters.recordObservation();

            BurstState state = bursts.get(observation.tagId());

            /*
             * A periodic expiry call normally closes old passages. This check
             * also handles delayed scheduling: a new read must never be merged
             * into a passage whose quiet/max deadline already passed.
             */
            if (state != null && state.expired(now, policy)) {
                bursts.remove(observation.tagId());
                validObservation = state.validObservation();
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

        sendValidObservation(validObservation);
    }

    /**
     * Closes every passage whose quiet or maximum duration has expired.
     *
     * <p>The caller decides when this method runs. Runtime code uses
     * TagProcessingExpiryScheduler; deterministic tests may call it directly.</p>
     */
    void expire() {
        List<TagObservation> validObservations = new ArrayList<>();
        long now = monotonicClock.nowNanos();

        synchronized (lock) {
            Iterator<Map.Entry<TagId, BurstState>> iterator =
                    bursts.entrySet().iterator();
            while (iterator.hasNext()) {
                BurstState state = iterator.next().getValue();
                if (state.expired(now, policy)) {
                    iterator.remove();
                    validObservations.add(state.validObservation());
                    counters.recordClosedBurst();
                }
            }
        }

        for (TagObservation observation : validObservations) {
            sendValidObservation(observation);
        }
    }

    private void sendValidObservation(TagObservation observation) {
        if (observation == null) {
            return;
        }
        try {
            validObservationCallback.accept(observation);
        } catch (RuntimeException ex) {
            /*
             * One failed mapping/admission attempt must not corrupt filter state
             * or prevent later RFID passages from being processed.
             */
            LOG.warn(
                    "Could not process valid observation for {}",
                    observation.tagId().value(),
                    ex);
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

        private TagObservation validObservation() {
            return new TagObservation(
                    tagId,
                    maxRssi,
                    maxRssiObservedAt);
        }
    }
}
