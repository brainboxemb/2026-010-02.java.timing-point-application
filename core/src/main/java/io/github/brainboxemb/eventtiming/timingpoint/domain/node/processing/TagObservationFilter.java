package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
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

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Passive passage state called only on the TagProcessor scheduler thread.
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

    /** Creates a passage filter with a fixed processing policy. */
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

    /** Creates a passage filter that follows the live TagProcessor policy configuration. */
    TagObservationFilter(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            BiConsumer<RegistrationId, TimingTimestamp>
                    validObservationCallback) {
        failIfInvalidArgument(
                policyConfiguration != null,
                "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");
        failIfInvalidArgument(
                validObservationCallback != null,
                "validObservationCallback must not be null");
        this.policyConfiguration = policyConfiguration;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
        this.validObservationCallback = validObservationCallback;
    }

    /** Adds one mapped observation to the RegistrationId's current passage burst. */
    void add(
            RegistrationId registrationId,
            TagObservation observation) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(observation != null, "observation must not be null");

        ClosedBurst closedBurst = null;
        long now = monotonicClock.nowNanos();
        TagProcessingPolicy policy = policyConfiguration.currentValue();
        BurstState state = bursts.get(registrationId);

        if (state != null && state.expired(now, policy)) {
            bursts.remove(registrationId);
            closedBurst = state.close(registrationId);
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

    /** Closes expired passage bursts and publishes their selected observations. */
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
                closedBursts.add(state.close(entry.getKey()));
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
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return bursts.remove(registrationId) != null;
    }

    /** Reports whether one or more passage bursts are still being aggregated. */
    boolean hasPendingState() {
        return !bursts.isEmpty();
    }

    /** Returns diagnostic snapshots of the currently open passage bursts. */
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


    /** Publishes one closed burst's selected observation when a burst was actually closed. */
    private void sendValidObservation(ClosedBurst closedBurst) {
        if (closedBurst == null) {
            return;
        }
        LOG.info(
                "Tag passage selected: registrationId={}, tagId={}, rssi={}, observedAt={}",
                closedBurst.registrationId.value(),
                closedBurst.tagId.value(),
                closedBurst.rssi,
                closedBurst.observedAt);

        validObservationCallback.accept(
                closedBurst.registrationId,
                closedBurst.observedAt);
    }

    private static final class ClosedBurst {
        private final RegistrationId registrationId;
        private final TagId tagId;
        private final int rssi;
        private final TimingTimestamp observedAt;

        /** Captures the immutable selected observation of one closed registration burst. */
        private ClosedBurst(
                RegistrationId registrationId,
                TagId tagId,
                int rssi,
                TimingTimestamp observedAt) {
            this.registrationId = registrationId;
            this.tagId = tagId;
            this.rssi = rssi;
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

        /** Starts a mutable passage burst from its first observation. */
        private BurstState(TagObservation observation, long now) {
            firstSeenNanos = now;
            lastSeenNanos = now;
            maxRssi = observation.rssi();
            maxRssiTagId = observation.tagId();
            maxRssiObservedAt = observation.observedAt();
            recordTag(
                    observation);
        }

        /** Adds one observation and updates the burst's maximum-RSSI selection. */
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

        /** Updates per-TagId diagnostic statistics for one observation. */
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
            } else {
                stats.add(
                        observation);
            }
        }

        /** Reports whether quiet-time or maximum-duration closure is due. */
        private boolean expired(long now, TagProcessingPolicy policy) {
            return now - lastSeenNanos >= policy.quietTimeoutNanos()
                    || now - firstSeenNanos >= policy.maxBurstDurationNanos();
        }

        /** Freezes the burst's selected maximum-RSSI observation. */
        private ClosedBurst close(
                RegistrationId registrationId) {
            return new ClosedBurst(
                    registrationId,
                    maxRssiTagId,
                    maxRssi,
                    maxRssiObservedAt);
        }

        /** Builds the engineering snapshot for this still-open burst. */
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

        /** Starts per-tag diagnostics from the first observation. */
        private MutableTagStats(
                TagObservation observation) {
            tagId = observation.tagId();
            observationCount = 1L;
            strongestRssi = observation.rssi();
            firstObservedAt = observation.observedAt();
            lastObservedAt = observation.observedAt();
        }

        /** Updates per-tag diagnostics with one additional observation. */
        private void add(
                TagObservation observation) {
            observationCount++;
            lastObservedAt = observation.observedAt();
            if (observation.rssi() > strongestRssi) {
                strongestRssi = observation.rssi();
            }
        }

        /** Returns the immutable diagnostic statistics for this TagId. */
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
