package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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

    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final MonotonicClock monotonicClock;
    private final TagProcessingMetrics metrics;
    private final BiConsumer<RegistrationId, TimingTimestamp>
            validObservationCallback;

    private final Map<RegistrationId, BurstState> bursts = new HashMap<>();

    TagObservationFilter(
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            BiConsumer<RegistrationId, TimingTimestamp>
                    validObservationCallback) {
        this(
                ReadOnlyConfiguration.fixed(policy),
                monotonicClock,
                metrics,
                validObservationCallback);
    }

    TagObservationFilter(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            BiConsumer<RegistrationId, TimingTimestamp>
                    validObservationCallback) {
        if (policyConfiguration == null) {
            throw new IllegalArgumentException(
                    "policyConfiguration must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (validObservationCallback == null) {
            throw new IllegalArgumentException(
                    "validObservationCallback must not be null");
        }
        this.policyConfiguration = policyConfiguration;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
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
        TagProcessingPolicy policy = policyConfiguration.currentValue();
        BurstState state = bursts.get(registrationId);

        if (state != null && state.expired(now, policy)) {
            bursts.remove(registrationId);
            closedBurst = new ClosedBurst(
                    registrationId,
                    state.maxRssiObservedAt());
            metrics.recordClosedBurst();
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
        TagProcessingPolicy policy = policyConfiguration.currentValue();

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
                metrics.recordClosedBurst();
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

    List<TagPassageSnapshot> snapshots() {
        List<TagPassageSnapshot> result =
                new ArrayList<TagPassageSnapshot>(
                        bursts.size());
        for (Map.Entry<RegistrationId, BurstState> entry
                : bursts.entrySet()) {
            result.add(
                    entry.getValue()
                            .snapshot(
                                    entry.getKey()));
        }
        return result;
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
        private final Map<TagId, MutableTagStats> tagStats =
                new LinkedHashMap<TagId, MutableTagStats>();

        private long lastSeenNanos;
        private int maxRssi;
        private TagId maxRssiTagId;
        private TimingTimestamp maxRssiObservedAt;

        private BurstState(TagObservation observation, long now) {
            firstSeenNanos = now;
            lastSeenNanos = now;
            maxRssi = observation.rssi();
            maxRssiTagId = observation.tagId();
            maxRssiObservedAt = observation.observedAt();
            recordTag(
                    observation);
        }

        private void add(TagObservation observation, long now) {
            lastSeenNanos = now;
            recordTag(
                    observation);
            if (observation.rssi() > maxRssi) {
                maxRssi = observation.rssi();
                maxRssiTagId = observation.tagId();
                maxRssiObservedAt = observation.observedAt();
            }
        }

        private void recordTag(
                TagObservation observation) {
            MutableTagStats stats =
                    tagStats.get(
                            observation.tagId());
            if (stats == null) {
                tagStats.put(
                        observation.tagId(),
                        new MutableTagStats(
                                observation));
                return;
            }
            stats.add(
                    observation);
        }

        private boolean expired(long now, TagProcessingPolicy policy) {
            return now - lastSeenNanos >= policy.quietTimeoutNanos()
                    || now - firstSeenNanos >= policy.maxBurstDurationNanos();
        }

        private TimingTimestamp maxRssiObservedAt() {
            return maxRssiObservedAt;
        }

        private TagPassageSnapshot snapshot(
                RegistrationId registrationId) {
            List<TagPassageSnapshot.TagStats> tags =
                    new ArrayList<TagPassageSnapshot.TagStats>(
                            tagStats.size());
            for (MutableTagStats stats
                    : tagStats.values()) {
                tags.add(
                        stats.snapshot());
            }

            return new TagPassageSnapshot(
                    registrationId,
                    tags,
                    maxRssiTagId,
                    maxRssi,
                    maxRssiObservedAt);
        }
    }

    private static final class MutableTagStats {
        private final TagId tagId;
        private long observationCount;
        private int strongestRssi;
        private final TimingTimestamp firstObservedAt;
        private TimingTimestamp lastObservedAt;

        private MutableTagStats(
                TagObservation observation) {
            tagId = observation.tagId();
            observationCount = 1L;
            strongestRssi = observation.rssi();
            firstObservedAt = observation.observedAt();
            lastObservedAt = observation.observedAt();
        }

        private void add(
                TagObservation observation) {
            observationCount++;
            lastObservedAt = observation.observedAt();
            if (observation.rssi() > strongestRssi) {
                strongestRssi = observation.rssi();
            }
        }

        private TagPassageSnapshot.TagStats snapshot() {
            return new TagPassageSnapshot.TagStats(
                    tagId,
                    observationCount,
                    strongestRssi,
                    firstObservedAt,
                    lastObservedAt);
        }
    }
}
