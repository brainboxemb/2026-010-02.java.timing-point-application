package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.memory.ReusableObjectPool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Groups mapped antenna observations into one passage per RegistrationId.
 *
 * <p>Every observation carries two time bases. Monotonic nanoseconds decide
 * whether observations belong to the same passage; epoch milliseconds are
 * retained only as the absolute event times that may later be persisted.</p>
 *
 * <p>A new passage starts when the gap from the previous observation reaches
 * the quiet timeout or when the span from the first observation reaches the
 * maximum passage duration. Both boundaries are inclusive. Scheduler or queue
 * delay after the antenna observation therefore cannot change passage grouping.</p>
 *
 * <p>One passage contains observations for one RegistrationId and at most two
 * physical tags. Each pooled RegistrationPassage owns two reusable TagState
 * slots that retain first/last absolute observation time plus strongest-signal
 * information for later registration detail persistence.</p>
 *
 * <p>All calls are owned by the TagProcessor scheduler thread. The filter and
 * its reusable passage pool therefore need no locking.</p>
 */
final class TagObservationFilter {
    private static final Logger LOG = LoggerFactory.getLogger(TagObservationFilter.class);

    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final MonotonicClock monotonicClock;
    private final TagProcessingMetrics metrics;
    private final BiConsumer<RegistrationId, TimingTimestamp> validObservationCallback;

    private final Map<RegistrationId, RegistrationPassage> passages =
            new HashMap<RegistrationId, RegistrationPassage>();
    private final ArrayList<RegistrationPassage> expiredPassages =
            new ArrayList<RegistrationPassage>();
    private final ReusableObjectPool<RegistrationPassage> passagePool;

    /** Creates a passage filter with one fixed processing policy. */
    TagObservationFilter(
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            BiConsumer<RegistrationId, TimingTimestamp> validObservationCallback) {
        this(
                ReadOnlyConfiguration.fixed(policy),
                monotonicClock,
                metrics,
                validObservationCallback);
    }

    /**
     * Creates a filter whose thresholds follow live configuration.
     *
     * <p>The initial observation queue capacity also sizes the pre-created
     * passage pool so normal passage creation does not allocate.</p>
     */
    TagObservationFilter(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            BiConsumer<RegistrationId, TimingTimestamp> validObservationCallback) {
        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");
        failIfInvalidArgument(validObservationCallback != null, "validObservationCallback must not be null");

        TagProcessingPolicy startupPolicy = policyConfiguration.startupValue();
        failIfInvalidArgument(startupPolicy != null, "policyConfiguration.startupValue must not be null");

        this.policyConfiguration = policyConfiguration;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
        this.validObservationCallback = validObservationCallback;
        passagePool = new ReusableObjectPool<RegistrationPassage>(
                startupPolicy.observationQueueCapacity(),
                RegistrationPassage::new);
        recordPoolMetrics();
    }

    /**
     * Adds one mapped antenna observation to its RegistrationId passage.
     *
     * <p>If the observation crosses a measured passage boundary, the old
     * passage is detached and the incoming observation starts the next passage
     * before the old passage is published. This ordering is required because
     * publication starts duplicate suppression, which may discard that
     * just-started next passage.</p>
     */
    void add(
            RegistrationId registrationId,
            TagObservation observation) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(observation != null, "observation must not be null");

        TagProcessingPolicy policy = policyConfiguration.currentValue();
        RegistrationPassage passage = passages.get(registrationId);
        RegistrationPassage completedPassage = null;

        if (passage != null && passage.startsNewPassage(observation, policy)) {
            passages.remove(registrationId);
            completedPassage = passage;
            metrics.recordClosedPassage();
            passage = null;
        }

        if (passage == null) {
            passage = passagePool.acquire();
            passage.reset(registrationId, observation);
            passages.put(registrationId, passage);
            recordPoolMetrics();
        } else {
            passage.add(observation);
        }

        publishAndRelease(completedPassage);
    }

    /**
     * Flushes passages for which no later antenna observation arrived.
     *
     * <p>The current monotonic clock is compared with the monotonic timestamp
     * captured on the last observation. This is only an idle-publication rule;
     * it does not redefine the absolute event time selected for the registration.</p>
     */
    void periodic() {
        expiredPassages.clear();

        long nowNanos = monotonicClock.nowNanos();
        long quietTimeoutNanos = policyConfiguration.currentValue().quietTimeoutNanos();
        Iterator<Map.Entry<RegistrationId, RegistrationPassage>> iterator =
                passages.entrySet().iterator();

        while (iterator.hasNext()) {
            RegistrationPassage passage = iterator.next().getValue();
            if (passage.flushDue(nowNanos, quietTimeoutNanos)) {
                iterator.remove();
                expiredPassages.add(passage);
                metrics.recordClosedPassage();
            }
        }

        for (int index = 0; index < expiredPassages.size(); index++) {
            publishAndRelease(expiredPassages.get(index));
        }
        expiredPassages.clear();
    }

    /** Discards one open passage when duplicate suppression makes it irrelevant. */
    boolean discard(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");

        RegistrationPassage passage = passages.remove(registrationId);
        if (passage == null) {
            return false;
        }

        release(passage);
        return true;
    }

    /** Reports whether one or more RegistrationId passages are still open. */
    boolean hasPendingState() {
        return !passages.isEmpty();
    }

    /**
     * Publishes the strongest observation selected from a completed passage.
     *
     * <p>Only this boundary reconstructs TimingTimestamp and TagId objects from
     * primitive passage state. The passage is always returned to the pool, even
     * when a subscriber throws.</p>
     */
    private void publishAndRelease(RegistrationPassage passage) {
        if (passage == null) {
            return;
        }

        RegistrationId registrationId = RegistrationId.fromPackedValue(passage.registrationId);
        TagState selectedTag = passage.selectedTag();
        TimingTimestamp observedAt = new TimingTimestamp(
                Instant.ofEpochMilli(selectedTag.strongestObservedAtEpochMillis));

        try {
            if (LOG.isInfoEnabled()) {
                LOG.info(
                        "Tag passage selected: registrationId={}, tagId={}, rssi={}, observedAt={}",
                        registrationId,
                        TagId.fromPackedValue(selectedTag.tagId),
                        selectedTag.strongestRssi,
                        observedAt);
            }
            validObservationCallback.accept(registrationId, observedAt);
        } finally {
            release(passage);
        }
    }

    /** Clears logical state, returns one passage to the pool and updates pool occupancy metrics. */
    private void release(RegistrationPassage passage) {
        passage.clear();
        passagePool.release(passage);
        recordPoolMetrics();
    }

    /** Copies allocation-free pool counters into the TagProcessor metric source. */
    private void recordPoolMetrics() {
        metrics.recordPassagePool(
                passagePool.initialSize(),
                passagePool.size(),
                passagePool.inUse(),
                passagePool.peakInUse());
    }

    /**
     * Mutable pooled state for one currently observed registration passage.
     *
     * <p>The object retains both absolute first/last event time and monotonic
     * first/last observation time. Absolute values describe what happened;
     * monotonic values are used only for elapsed passage rules.</p>
     */
    private static final class RegistrationPassage {
        private final TagState[] tags = {
                new TagState(),
                new TagState()
        };

        private int registrationId;
        private int tagCount;
        private long firstObservedAtEpochMillis;
        private long lastObservedAtEpochMillis;
        private long firstObservedAtMonotonicNanos;
        private long lastObservedAtMonotonicNanos;

        /** Reinitializes this pooled object from the first observation of a new passage. */
        private void reset(
                RegistrationId registrationId,
                TagObservation observation) {
            this.registrationId = registrationId.packedValue();
            tagCount = 0;
            firstObservedAtEpochMillis = observation.observedAtEpochMillis();
            lastObservedAtEpochMillis = firstObservedAtEpochMillis;
            firstObservedAtMonotonicNanos = observation.observedAtMonotonicNanos();
            lastObservedAtMonotonicNanos = firstObservedAtMonotonicNanos;
            record(observation);
        }

        /** Appends one observation in antenna-observation order and updates its physical-tag state. */
        private void add(TagObservation observation) {
            lastObservedAtEpochMillis = observation.observedAtEpochMillis();
            lastObservedAtMonotonicNanos = observation.observedAtMonotonicNanos();
            record(observation);
        }

        /**
         * Determines whether the incoming observation starts a later passage.
         *
         * <p>Two independent elapsed-time rules close the current passage:</p>
         * <ul>
         *   <li>the gap from the previous observation is greater than or equal
         *       to the configured quiet timeout; or</li>
         *   <li>the span from the first observation is greater than or equal
         *       to the configured maximum passage duration.</li>
         * </ul>
         *
         * <p>Both differences use antenna-captured monotonic nanoseconds. Wall
         * clock corrections and later queue/scheduler delay cannot affect the
         * decision.</p>
         */
        private boolean startsNewPassage(
                TagObservation observation,
                TagProcessingPolicy policy) {
            long observedAtNanos = observation.observedAtMonotonicNanos();
            long quietGapNanos = observedAtNanos - lastObservedAtMonotonicNanos;
            long passageDurationNanos = observedAtNanos - firstObservedAtMonotonicNanos;

            return quietGapNanos >= policy.quietTimeoutNanos()
                    || passageDurationNanos >= policy.maxPassageDurationNanos();
        }

        /**
         * Reports whether an open passage has received no observation for the quiet timeout.
         *
         * <p>The same monotonic time base is used for the captured observation
         * and current runtime clock, so absolute clock changes are irrelevant.</p>
         */
        private boolean flushDue(
                long nowNanos,
                long quietTimeoutNanos) {
            return nowNanos - lastObservedAtMonotonicNanos >= quietTimeoutNanos;
        }

        /**
         * Records the observation in one of the two fixed physical-tag slots.
         *
         * <p>The first distinct TagId takes slot 0 and an optional second TagId
         * takes slot 1. A third distinct TagId for one RegistrationId violates
         * the supported passage model and is rejected.</p>
         */
        private void record(TagObservation observation) {
            int tagId = observation.tagId().packedValue();
            TagState tag = null;

            for (int index = 0; index < tagCount; index++) {
                if (tags[index].tagId == tagId) {
                    tag = tags[index];
                    break;
                }
            }

            if (tag == null) {
                failIfIllegalState(tagCount < tags.length, "RegistrationPassage supports at most two TagIds");
                tag = tags[tagCount++];
                tag.reset(observation);
            } else {
                tag.record(observation);
            }
        }

        /**
         * Selects the physical tag that supplied the strongest observation.
         *
         * <p>Higher RSSI wins. If both tags reached the same strongest RSSI,
         * the tag that reached it first on the monotonic observation timeline
         * wins, preserving the earlier measurement.</p>
         */
        private TagState selectedTag() {
            failIfIllegalState(tagCount > 0, "RegistrationPassage has no TagId state");

            TagState selected = tags[0];
            for (int index = 1; index < tagCount; index++) {
                if (tags[index].strongerThan(selected)) {
                    selected = tags[index];
                }
            }
            return selected;
        }

        /** Clears logical ownership before this pooled object is reused for another passage. */
        private void clear() {
            registrationId = 0;
            tagCount = 0;
            firstObservedAtEpochMillis = 0L;
            lastObservedAtEpochMillis = 0L;
            firstObservedAtMonotonicNanos = 0L;
            lastObservedAtMonotonicNanos = 0L;
        }
    }

    /**
     * Reusable statistics for one physical tag observed in a passage.
     *
     * <p>First/last and strongest absolute times remain available for a future
     * persisted passage-detail record. The strongest monotonic time is retained
     * only to resolve equal-RSSI observations in observation order.</p>
     */
    private static final class TagState {
        private int tagId;
        private long firstObservedAtEpochMillis;
        private long lastObservedAtEpochMillis;
        private int strongestRssi;
        private long strongestObservedAtEpochMillis;
        private long strongestObservedAtMonotonicNanos;
        private long observationCount;

        /** Starts this reusable slot from the first observation of one physical tag. */
        private void reset(TagObservation observation) {
            tagId = observation.tagId().packedValue();
            firstObservedAtEpochMillis = observation.observedAtEpochMillis();
            lastObservedAtEpochMillis = firstObservedAtEpochMillis;
            strongestRssi = observation.rssi();
            strongestObservedAtEpochMillis = firstObservedAtEpochMillis;
            strongestObservedAtMonotonicNanos = observation.observedAtMonotonicNanos();
            observationCount = 1L;
        }

        /**
         * Updates first/last history and replaces the selected observation only for stronger RSSI.
         *
         * <p>Equal RSSI deliberately keeps the earlier strongest observation.</p>
         */
        private void record(TagObservation observation) {
            observationCount++;
            lastObservedAtEpochMillis = observation.observedAtEpochMillis();

            if (observation.rssi() > strongestRssi) {
                strongestRssi = observation.rssi();
                strongestObservedAtEpochMillis = observation.observedAtEpochMillis();
                strongestObservedAtMonotonicNanos = observation.observedAtMonotonicNanos();
            }
        }

        /** Orders two tag slots by strongest RSSI and then earliest strongest monotonic observation. */
        private boolean strongerThan(TagState other) {
            return strongestRssi > other.strongestRssi
                    || strongestRssi == other.strongestRssi
                            && strongestObservedAtMonotonicNanos < other.strongestObservedAtMonotonicNanos;
        }
    }
}
