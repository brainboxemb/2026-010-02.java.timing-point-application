package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.CompactTimingTime;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Groups mapped tag observations into one passage per RegistrationId.
 *
 * <p>A passage starts with the first observation for a RegistrationId. Later
 * observations update only the last-seen time and the strongest RSSI
 * selection. The selected observation is therefore the strongest observation
 * seen during the passage; equal RSSI keeps the earlier observation.</p>
 *
 * <p>A passage closes after the configured quiet timeout or maximum passage
 * duration. Closing removes the passage from {@code bursts} before publishing
 * its selected observation. This matters because the publication callback may
 * start duplicate suppression and discard a newly started passage for the same
 * RegistrationId.</p>
 *
 * <p>All methods are called on the TagProcessor scheduler thread. The filter
 * therefore needs no internal locking. Periodic sweeps reuse one
 * {@code expiredBursts} list; no temporary list or immutable diagnostic
 * snapshot is created for every sweep or observation.</p>
 */
final class TagObservationFilter {
    private static final Logger LOG =
            LoggerFactory.getLogger(TagObservationFilter.class);

    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final MonotonicClock monotonicClock;
    private final TagProcessingMetrics metrics;
    private final BiConsumer<RegistrationId, TimingTimestamp>
            validObservationCallback;

    private final Map<RegistrationId, BurstState> bursts =
            new HashMap<RegistrationId, BurstState>();
    private final ArrayList<BurstState> expiredBursts =
            new ArrayList<BurstState>();

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
        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");
        failIfInvalidArgument(validObservationCallback != null, "validObservationCallback must not be null");
        this.policyConfiguration = policyConfiguration;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
        this.validObservationCallback = validObservationCallback;
    }

    /**
     * Adds one mapped observation to its RegistrationId passage.
     *
     * <p>If the previous passage already expired, that passage is removed,
     * a new passage is started for the incoming observation, and only then is
     * the previous selected observation published. The callback can therefore
     * safely discard the newly started passage when duplicate suppression
     * begins.</p>
     */
    void add(
            RegistrationId registrationId,
            TagObservation observation) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(observation != null, "observation must not be null");

        BurstState closedBurst = null;
        long now = monotonicClock.nowNanos();
        TagProcessingPolicy policy = policyConfiguration.currentValue();
        BurstState state = bursts.get(registrationId);

        if (state != null && state.expired(now, policy)) {
            bursts.remove(registrationId);
            closedBurst = state;
            metrics.recordClosedBurst();
            state = null;
        }

        if (state == null) {
            bursts.put(
                    registrationId,
                    new BurstState(
                            registrationId,
                            observation,
                            now));
        } else {
            state.add(
                    observation,
                    now);
        }

        sendValidObservation(closedBurst);
    }

    /**
     * Closes every passage that expired since the previous sweep.
     *
     * <p>Expired states are first removed while the map iterator is active and
     * stored in the reusable {@code expiredBursts} buffer. Publication happens
     * only after iteration, because the callback may mutate passage state.</p>
     */
    void periodic() {
        expiredBursts.clear();

        long now = monotonicClock.nowNanos();
        TagProcessingPolicy policy = policyConfiguration.currentValue();
        Iterator<Map.Entry<RegistrationId, BurstState>> iterator =
                bursts.entrySet().iterator();

        while (iterator.hasNext()) {
            BurstState state = iterator.next().getValue();
            if (state.expired(now, policy)) {
                iterator.remove();
                expiredBursts.add(state);
                metrics.recordClosedBurst();
            }
        }

        for (int index = 0; index < expiredBursts.size(); index++) {
            sendValidObservation(
                    expiredBursts.get(index));
        }
        expiredBursts.clear();
    }

    /** Discards any currently aggregated next passage for one RegistrationId. */
    boolean discard(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return bursts.remove(registrationId) != null;
    }

    /** Reports whether one or more passages are still open. */
    boolean hasPendingState() {
        return !bursts.isEmpty();
    }

    /** Publishes the strongest observation selected from one removed passage. */
    private void sendValidObservation(BurstState burst) {
        if (burst == null) {
            return;
        }

        TimingTimestamp observedAt =
                CompactTimingTime.toTimestamp(
                        burst.maxRssiObservedAt);
        LOG.info(
                "Tag passage selected: registrationId={}, tagId={}, rssi={}, observedAt={}",
                burst.registrationId,
                burst.maxRssiTagId,
                burst.maxRssi,
                observedAt);

        validObservationCallback.accept(
                burst.registrationId,
                observedAt);
    }

    /**
     * Mutable state for one currently visible RegistrationId passage.
     *
     * <p>Only the values needed to decide passage expiry and select the
     * strongest observation are retained. Per-tag diagnostic counters are not
     * part of passage filtering and therefore are not maintained here.</p>
     */
    private static final class BurstState {
        private final RegistrationId registrationId;
        private final long firstSeenNanos;

        private long lastSeenNanos;
        private int maxRssi;
        private TagId maxRssiTagId;
        private long maxRssiObservedAt;

        /** Starts one passage from its first observation. */
        private BurstState(
                RegistrationId registrationId,
                TagObservation observation,
                long now) {
            this.registrationId = registrationId;
            firstSeenNanos = now;
            lastSeenNanos = now;
            maxRssi = observation.rssi();
            maxRssiTagId = observation.tagId();
            maxRssiObservedAt = observation.observedAtValue();
        }

        /** Adds one observation and keeps the first observation at equal RSSI. */
        private void add(
                TagObservation observation,
                long now) {
            lastSeenNanos = now;
            if (observation.rssi() > maxRssi) {
                maxRssi = observation.rssi();
                maxRssiTagId = observation.tagId();
                maxRssiObservedAt = observation.observedAtValue();
            }
        }

        /** Reports whether quiet time or maximum passage duration has elapsed. */
        private boolean expired(
                long now,
                TagProcessingPolicy policy) {
            return now - lastSeenNanos >= policy.quietTimeoutNanos()
                    || now - firstSeenNanos >= policy.maxBurstDurationNanos();
        }
    }
}
