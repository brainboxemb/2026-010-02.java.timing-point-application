package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.CompactTimingTime;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.memory.ReusableObjectPool;

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
 * <p>Passage membership is based on the antenna observation time. A new
 * observation starts a new passage when its measured gap from the previous
 * observation reaches the quiet timeout, or when its measured distance from
 * the first observation reaches the maximum passage duration. Queue or
 * scheduler delay therefore does not define passage boundaries.</p>
 *
 * <p>Monotonic runtime time has a separate role. It is retained only to flush
 * an open passage after no further observation has been processed for the
 * configured quiet timeout. It never supplies the selected registration time.</p>
 *
 * <p>One passage may contain one or two physical tags. RegistrationPassage
 * retains fixed TagState[2] storage so first/last observation and strongest
 * signal information for both tags remains available for later completed
 * passage persistence. Passage and TagState objects are created once in a
 * reusable pool instead of per passage.</p>
 *
 * <p>All methods run on the TagProcessor scheduler thread. No filter or pool
 * locking is required.</p>
 */
final class TagObservationFilter {
    private static final Logger LOG =
            LoggerFactory.getLogger(TagObservationFilter.class);

    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final MonotonicClock monotonicClock;
    private final TagProcessingMetrics metrics;
    private final BiConsumer<RegistrationId, TimingTimestamp>
            validObservationCallback;

    private final Map<RegistrationId, RegistrationPassage> passages =
            new HashMap<RegistrationId, RegistrationPassage>();
    private final ArrayList<RegistrationPassage> expiredPassages =
            new ArrayList<RegistrationPassage>();
    private final ReusableObjectPool<RegistrationPassage> passagePool;

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

        TagProcessingPolicy startupPolicy =
                policyConfiguration.startupValue();
        failIfInvalidArgument(startupPolicy != null, "policyConfiguration.startupValue must not be null");

        this.policyConfiguration = policyConfiguration;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
        this.validObservationCallback = validObservationCallback;
        passagePool =
                new ReusableObjectPool<RegistrationPassage>(
                        startupPolicy.observationQueueCapacity(),
                        RegistrationPassage::new);
        recordPoolMetrics();
    }

    /**
     * Adds one mapped antenna observation to its RegistrationId passage.
     *
     * <p>An expired previous passage is removed first. The incoming observation
     * then starts its new passage before the previous passage is published,
     * preserving duplicate-suppression behavior from the existing flow.</p>
     */
    void add(
            RegistrationId registrationId,
            TagObservation observation) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(observation != null, "observation must not be null");

        long receivedAtNanos =
                monotonicClock.nowNanos();
        TagProcessingPolicy policy =
                policyConfiguration.currentValue();
        RegistrationPassage passage =
                passages.get(registrationId);
        RegistrationPassage completedPassage = null;

        if (passage != null
                && passage.startsNewPassage(
                        observation.observedAtValue(),
                        policy)) {
            passages.remove(registrationId);
            completedPassage = passage;
            metrics.recordClosedBurst();
            passage = null;
        }

        if (passage == null) {
            passage = passagePool.acquire();
            passage.reset(
                    registrationId,
                    observation,
                    receivedAtNanos);
            passages.put(
                    registrationId,
                    passage);
            recordPoolMetrics();
        } else {
            passage.add(
                    observation,
                    receivedAtNanos);
        }

        publishAndRelease(
                completedPassage);
    }

    /**
     * Flushes passages for which no later observation arrived.
     *
     * <p>The monotonic clock is intentionally used only here. It answers
     * whether an otherwise open passage has been locally quiet long enough to
     * publish; it does not determine whether two antenna observations belong to
     * the same measured passage.</p>
     */
    void periodic() {
        expiredPassages.clear();

        long nowNanos =
                monotonicClock.nowNanos();
        long quietTimeoutNanos =
                policyConfiguration.currentValue()
                        .quietTimeoutNanos();
        Iterator<Map.Entry<RegistrationId, RegistrationPassage>> iterator =
                passages.entrySet().iterator();

        while (iterator.hasNext()) {
            RegistrationPassage passage =
                    iterator.next().getValue();
            if (passage.flushDue(
                    nowNanos,
                    quietTimeoutNanos)) {
                iterator.remove();
                expiredPassages.add(passage);
                metrics.recordClosedBurst();
            }
        }

        for (int index = 0; index < expiredPassages.size(); index++) {
            publishAndRelease(
                    expiredPassages.get(index));
        }
        expiredPassages.clear();
    }

    /** Discards one open passage and immediately returns its state to the pool. */
    boolean discard(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");

        RegistrationPassage passage =
                passages.remove(registrationId);
        if (passage == null) {
            return false;
        }

        release(
                passage);
        return true;
    }

    /** Reports whether one or more passages are still open. */
    boolean hasPendingState() {
        return !passages.isEmpty();
    }

    /** Publishes the selected observation and then returns passage state to the pool. */
    private void publishAndRelease(
            RegistrationPassage passage) {
        if (passage == null) {
            return;
        }

        RegistrationId registrationId =
                RegistrationId.fromPackedValue(
                        passage.registrationId);
        TagState selectedTag =
                passage.selectedTag();
        TimingTimestamp observedAt =
                CompactTimingTime.toTimestamp(
                        selectedTag.strongestObservedAt);

        try {
            if (LOG.isInfoEnabled()) {
                LOG.info(
                        "Tag passage selected: registrationId={}, tagId={}, rssi={}, observedAt={}",
                        registrationId,
                        TagId.fromPackedValue(
                                selectedTag.tagId),
                        selectedTag.strongestRssi,
                        observedAt);
            }

            validObservationCallback.accept(
                    registrationId,
                    observedAt);
        } finally {
            release(
                    passage);
        }
    }

    /** Returns one completed/discarded passage to the pool and updates pool metrics. */
    private void release(
            RegistrationPassage passage) {
        passage.clear();
        passagePool.release(
                passage);
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
     * Mutable pooled state for one RegistrationId passage.
     *
     * <p>The two TagState objects and their array are created once with this
     * object. reset() only overwrites primitive fields for the next passage.</p>
     */
    private static final class RegistrationPassage {
        private final TagState[] tags = {
                new TagState(),
                new TagState()
        };

        private int registrationId;
        private int tagCount;
        private long firstObservedAt;
        private long lastObservedAt;
        private long lastReceivedAtNanos;

        /** Prepares this pooled passage for a new RegistrationId. */
        private void reset(
                RegistrationId registrationId,
                TagObservation observation,
                long receivedAtNanos) {
            this.registrationId =
                    registrationId.packedValue();
            tagCount = 0;
            firstObservedAt =
                    observation.observedAtValue();
            lastObservedAt =
                    firstObservedAt;
            lastReceivedAtNanos =
                    receivedAtNanos;
            record(
                    observation);
        }

        /** Adds one observation to this passage and its physical-tag statistics. */
        private void add(
                TagObservation observation,
                long receivedAtNanos) {
            long observedAt =
                    observation.observedAtValue();
            if (observedAt < firstObservedAt) {
                firstObservedAt = observedAt;
            }
            if (observedAt > lastObservedAt) {
                lastObservedAt = observedAt;
            }
            lastReceivedAtNanos =
                    receivedAtNanos;
            record(
                    observation);
        }

        /** Reports whether this measured observation belongs to a later passage. */
        private boolean startsNewPassage(
                long observedAt,
                TagProcessingPolicy policy) {
            long quietGap =
                    CompactTimingTime.elapsedCentiseconds(
                            lastObservedAt,
                            observedAt);
            long passageDuration =
                    CompactTimingTime.elapsedCentiseconds(
                            firstObservedAt,
                            observedAt);

            return quietGap >= policy.quietTimeoutCentiseconds()
                    || passageDuration
                            >= policy.maxPassageDurationCentiseconds();
        }

        /** Reports whether local quiet time allows this open passage to be flushed. */
        private boolean flushDue(
                long nowNanos,
                long quietTimeoutNanos) {
            return nowNanos - lastReceivedAtNanos
                    >= quietTimeoutNanos;
        }

        /** Records one physical tag in one of the two fixed reusable tag slots. */
        private void record(
                TagObservation observation) {
            int tagId =
                    observation.tagId()
                            .packedValue();
            TagState tag = null;

            for (int index = 0; index < tagCount; index++) {
                if (tags[index].tagId == tagId) {
                    tag = tags[index];
                    break;
                }
            }

            if (tag == null) {
                failIfIllegalState(
                        tagCount < tags.length,
                        "RegistrationPassage supports at most two TagIds");
                tag = tags[tagCount++];
                tag.reset(
                        tagId,
                        observation.rssi(),
                        observation.observedAtValue());
            } else {
                tag.record(
                        observation.rssi(),
                        observation.observedAtValue());
            }
        }

        /** Selects the strongest physical-tag observation retained by this passage. */
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

        /** Clears logical ownership before this object returns to the pool. */
        private void clear() {
            registrationId = 0;
            tagCount = 0;
            firstObservedAt = 0L;
            lastObservedAt = 0L;
            lastReceivedAtNanos = 0L;
        }
    }

    /** Reusable primitive statistics for one physical A/B tag in a passage. */
    private static final class TagState {
        private int tagId;
        private long firstObservedAt;
        private long lastObservedAt;
        private int strongestRssi;
        private long strongestObservedAt;
        private long observationCount;

        /** Starts this reusable slot from the first observation of one physical tag. */
        private void reset(
                int tagId,
                int rssi,
                long observedAt) {
            this.tagId = tagId;
            firstObservedAt = observedAt;
            lastObservedAt = observedAt;
            strongestRssi = rssi;
            strongestObservedAt = observedAt;
            observationCount = 1L;
        }

        /** Updates first/last timing and strongest RSSI information. */
        private void record(
                int rssi,
                long observedAt) {
            observationCount++;
            if (observedAt < firstObservedAt) {
                firstObservedAt = observedAt;
            }
            if (observedAt > lastObservedAt) {
                lastObservedAt = observedAt;
            }
            if (rssi > strongestRssi) {
                strongestRssi = rssi;
                strongestObservedAt = observedAt;
            }
        }

        /** Orders tag candidates by RSSI and then by earliest strongest observation. */
        private boolean strongerThan(
                TagState other) {
            return strongestRssi > other.strongestRssi
                    || strongestRssi == other.strongestRssi
                            && strongestObservedAt < other.strongestObservedAt;
        }
    }
}
