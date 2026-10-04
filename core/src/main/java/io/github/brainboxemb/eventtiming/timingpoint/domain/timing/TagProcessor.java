package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
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
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Groups decoded tag reads into passages and submits one selected registration.
 *
 * <p>Repeated reads for one TagId are kept as one small BurstState. The
 * observation with the highest RSSI supplies the effective registration time.
 * TimingNode still owns queue admission, sequence allocation, active location,
 * durable TimingData write and LogBook commit.</p>
 */
public final class TagProcessor implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TagProcessor.class);

    private final TimingNode timingNode;
    private final TagRegistrationMapper mapper;
    private final TagProcessingPolicy policy;
    private final MonotonicClock monotonicClock;
    private final Object burstLock = new Object();
    private final Object registrationLock = new Object();
    private final Map<TagId, BurstState> bursts = new HashMap<>();
    private final Map<RegistrationId, Long> acceptedRegistrations = new HashMap<>();
    private final ScheduledFuture<?> expiryTask;

    private final AtomicLong observationCount = new AtomicLong();
    private final AtomicLong closedBurstCount = new AtomicLong();
    private final AtomicLong mappedCount = new AtomicLong();
    private final AtomicLong unmappedCount = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();
    private final AtomicLong admittedCount = new AtomicLong();
    private final AtomicLong queueFullCount = new AtomicLong();
    private final AtomicLong nodeNotRunningCount = new AtomicLong();

    private volatile boolean closed;

    public TagProcessor(
            TimingNode timingNode,
            TagRegistrationMapper mapper,
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            ScheduledExecutorService scheduler) {
        if (timingNode == null) {
            throw new IllegalArgumentException("timingNode must not be null");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler must not be null");
        }

        this.timingNode = timingNode;
        this.mapper = mapper;
        this.policy = policy;
        this.monotonicClock = monotonicClock;
        this.expiryTask = scheduler.scheduleWithFixedDelay(
                this::runExpirySweep,
                policy.sweepCadenceNanos(),
                policy.sweepCadenceNanos(),
                TimeUnit.NANOSECONDS);
    }

    /** Accepts one decoded observation from an Antenna EventSource. */
    public void onObservation(TagObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }

        ClosedBurst expired = null;
        long now = monotonicClock.nowNanos();
        synchronized (burstLock) {
            requireOpen();
            observationCount.incrementAndGet();

            BurstState state = bursts.get(observation.tagId());
            if (state != null && state.expired(now, policy)) {
                bursts.remove(observation.tagId());
                expired = state.close();
                closedBurstCount.incrementAndGet();
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

        if (expired != null) {
            finishBurstSafely(expired);
        }
    }

    /**
     * Closes passages whose quiet or maximum duration has expired.
     *
     * <p>Package-private so deterministic tests can advance a MonotonicClock
     * without sleeping. Normal runtime use is through the single periodic
     * scheduler task created by the constructor.</p>
     */
    void expireBursts() {
        List<ClosedBurst> expired = new ArrayList<>();
        long now = monotonicClock.nowNanos();

        synchronized (burstLock) {
            if (closed) {
                return;
            }
            Iterator<Map.Entry<TagId, BurstState>> iterator =
                    bursts.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<TagId, BurstState> entry = iterator.next();
                BurstState state = entry.getValue();
                if (state.expired(now, policy)) {
                    iterator.remove();
                    expired.add(state.close());
                    closedBurstCount.incrementAndGet();
                }
            }
        }

        for (ClosedBurst burst : expired) {
            finishBurstSafely(burst);
        }
        removeExpiredDuplicateEntries(now);
    }

    private void runExpirySweep() {
        try {
            expireBursts();
        } catch (RuntimeException ex) {
            LOG.warn("TagProcessor expiry sweep failed", ex);
        }
    }

    private void finishBurstSafely(ClosedBurst burst) {
        try {
            finishBurst(burst);
        } catch (RuntimeException ex) {
            LOG.warn(
                    "TagProcessor could not process closed burst for {}",
                    burst.tagId.value(),
                    ex);
        }
    }

    private void finishBurst(ClosedBurst burst) {
        if (closed) {
            return;
        }

        RegistrationId registrationId = mapper.map(burst.tagId);
        if (registrationId == null) {
            unmappedCount.incrementAndGet();
            return;
        }
        mappedCount.incrementAndGet();

        synchronized (registrationLock) {
            if (closed) {
                return;
            }

            long now = monotonicClock.nowNanos();
            Long acceptedAt = acceptedRegistrations.get(registrationId);
            if (acceptedAt != null
                    && policy.duplicateWindowNanos() > 0L
                    && now - acceptedAt < policy.duplicateWindowNanos()) {
                duplicateCount.incrementAndGet();
                return;
            }
            if (acceptedAt != null) {
                acceptedRegistrations.remove(registrationId);
            }

            CommandAdmission admission =
                    timingNode.submit(
                            TimingNodeCommands.addAutomaticRegistration(
                                    registrationId,
                                    burst.observedAt));
            switch (admission) {
                case ACCEPTED:
                    admittedCount.incrementAndGet();
                    if (policy.duplicateWindowNanos() > 0L) {
                        acceptedRegistrations.put(registrationId, now);
                    }
                    break;
                case FULL:
                    queueFullCount.incrementAndGet();
                    break;
                case NOT_RUNNING:
                    nodeNotRunningCount.incrementAndGet();
                    break;
                default:
                    throw new IllegalStateException(
                            "Unsupported TimingNode admission result " + admission);
            }
        }
    }

    private void removeExpiredDuplicateEntries(long now) {
        if (policy.duplicateWindowNanos() == 0L) {
            return;
        }
        synchronized (registrationLock) {
            Iterator<Map.Entry<RegistrationId, Long>> iterator =
                    acceptedRegistrations.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<RegistrationId, Long> entry = iterator.next();
                if (now - entry.getValue() >= policy.duplicateWindowNanos()) {
                    iterator.remove();
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (burstLock) {
            if (closed) {
                return;
            }
            closed = true;
            bursts.clear();
        }
        expiryTask.cancel(false);
        synchronized (registrationLock) {
            acceptedRegistrations.clear();
        }
    }

    public long observationCount() {
        return observationCount.get();
    }

    public long closedBurstCount() {
        return closedBurstCount.get();
    }

    public long mappedCount() {
        return mappedCount.get();
    }

    public long unmappedCount() {
        return unmappedCount.get();
    }

    public long duplicateCount() {
        return duplicateCount.get();
    }

    public long admittedCount() {
        return admittedCount.get();
    }

    public long queueFullCount() {
        return queueFullCount.get();
    }

    public long nodeNotRunningCount() {
        return nodeNotRunningCount.get();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("TagProcessor is closed");
        }
    }

    private static final class BurstState {
        private final TagId tagId;
        private final long firstSeenNanos;
        private long lastSeenNanos;
        private int maxRssi;
        private TimingTimestamp maxRssiObservedAt;
        private int observationCount;

        private BurstState(TagObservation observation, long now) {
            tagId = observation.tagId();
            firstSeenNanos = now;
            lastSeenNanos = now;
            maxRssi = observation.rssi();
            maxRssiObservedAt = observation.observedAt();
            observationCount = 1;
        }

        private void add(TagObservation observation, long now) {
            lastSeenNanos = now;
            observationCount++;
            if (observation.rssi() > maxRssi) {
                maxRssi = observation.rssi();
                maxRssiObservedAt = observation.observedAt();
            }
        }

        private boolean expired(long now, TagProcessingPolicy policy) {
            return now - lastSeenNanos >= policy.quietTimeoutNanos()
                    || now - firstSeenNanos >= policy.maxBurstDurationNanos();
        }

        private ClosedBurst close() {
            return new ClosedBurst(
                    tagId,
                    maxRssi,
                    maxRssiObservedAt,
                    observationCount);
        }
    }

    private static final class ClosedBurst {
        private final TagId tagId;
        private final int maxRssi;
        private final TimingTimestamp observedAt;
        private final int observationCount;

        private ClosedBurst(
                TagId tagId,
                int maxRssi,
                TimingTimestamp observedAt,
                int observationCount) {
            this.tagId = tagId;
            this.maxRssi = maxRssi;
            this.observedAt = observedAt;
            this.observationCount = observationCount;
        }
    }
}
