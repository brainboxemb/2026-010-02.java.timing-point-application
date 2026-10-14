package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ConfigurationChange;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.scheduling.LaneScheduler;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Active tag-processing boundary between antenna observations and registrations.
 *
 * <p>The antenna callback only performs bounded queue ingress. EventData resolution,
 * duplicate suppression and passage selection run on one serial lane owned by
 * this processor. A completed passage is published as a {@link NewRegistration};
 * TagProcessor does not know where that registration is stored.</p>
 */
public final class TagProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(TagProcessor.class);

    private enum State {
        NEW,
        ACTIVE,
        INACTIVE
    }

    private final EventData eventData;
    private final Event<NewRegistration> newRegistrationEvent =
            new Event<NewRegistration>();
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final RegistrationDuplicateFilter duplicateFilter;
    private final TagProcessingMetrics metrics;
    private final TagObservationFilter observationFilter;
    private final SerialLane lane;
    private final LaneScheduler scheduler;
    private final ArrayBlockingQueue<TagObservation> inputQueue;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    private volatile State state = State.NEW;
    private volatile LaneScheduler.ScheduledRegistration housekeepingTask;
    private volatile List<TagPassageSnapshot> passageSnapshots =
            Collections.emptyList();

    public TagProcessor(
            EventData eventData,
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            SerialLane lane,
            ScheduledExecutorService scheduledExecutor) {
        this(
                eventData,
                ReadOnlyConfiguration.fixed(policy),
                monotonicClock,
                metrics,
                lane,
                scheduledExecutor);
    }

    public TagProcessor(
            EventData eventData,
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            SerialLane lane,
            ScheduledExecutorService scheduledExecutor) {
        if (eventData == null) {
            throw new IllegalArgumentException("eventData must not be null");
        }
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
        if (lane == null) {
            throw new IllegalArgumentException("lane must not be null");
        }
        if (scheduledExecutor == null) {
            throw new IllegalArgumentException("scheduledExecutor must not be null");
        }

        TagProcessingPolicy startupPolicy = policyConfiguration.startupValue();
        if (startupPolicy == null) {
            throw new IllegalArgumentException(
                    "policyConfiguration.startupValue must not be null");
        }

        this.eventData = eventData;
        this.policyConfiguration = policyConfiguration;
        this.metrics = metrics;
        this.lane = lane;
        this.scheduler =
                new LaneScheduler(
                        lane,
                        scheduledExecutor);
        this.inputQueue =
                new ArrayBlockingQueue<>(
                        startupPolicy.observationQueueCapacity());
        duplicateFilter =
                new RegistrationDuplicateFilter(
                        policyConfiguration,
                        monotonicClock);
        observationFilter =
                new TagObservationFilter(
                        policyConfiguration,
                        monotonicClock,
                        metrics,
                        this::processValidObservation);
    }

    public void activate() {
        synchronized (lifecycleLock) {
            if (state == State.ACTIVE) {
                throw new IllegalStateException("TagProcessor is already active");
            }
            lane.start();
            state = State.ACTIVE;
        }
    }

    public void deactivate() {
        boolean drainRemaining;
        synchronized (lifecycleLock) {
            if (state == State.INACTIVE) {
                return;
            }
            if (state == State.NEW) {
                state = State.INACTIVE;
                lane.close();
                inputQueue.clear();
                return;
            }

            state = State.INACTIVE;
            closeHousekeepingLocked();
            drainRemaining = !inputQueue.isEmpty();
            if (drainRemaining && !trySubmitToTagLane(this::drainAll)) {
                inputQueue.clear();
            }
        }

        lane.close();
        inputQueue.clear();
    }

    /**
     * Antenna tag-observed EventSource callback. It does no mapping/filtering on the provider
     * thread.
     */
    public void onTagObserved(TagObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }

        metrics.recordObservation();

        synchronized (lifecycleLock) {
            if (state != State.ACTIVE) {
                metrics.recordProcessorNotRunning();
                return;
            }
            if (!inputQueue.offer(observation)) {
                metrics.recordObservationQueueFull();
                return;
            }
            scheduleDrainLocked();
        }
    }

    /**
     * Returns the latest immutable engineering view of active registration passages.
     *
     * <p>The returned list is published by the TagProcessor serial lane. Callers
     * never read the mutable filter state directly and no worker round-trip is
     * required merely to inspect diagnostics.</p>
     */
    public List<TagPassageSnapshot> passageSnapshots() {
        return passageSnapshots;
    }

    /** Selected registrations published synchronously from the TagProcessor lane. */
    public EventSource<NewRegistration> newRegistrationEvent() {
        return newRegistrationEvent;
    }

    private void scheduleDrainLocked() {
        if (!drainScheduled.compareAndSet(false, true)) {
            return;
        }
        if (!trySubmitToTagLane(this::drainBatch)) {
            drainScheduled.set(false);
            metrics.recordProcessorNotRunning();
        }
    }

    private void drainBatch() {
        try {
            int count = inputQueue.size();
            for (int index = 0; index < count; index++) {
                TagObservation observation = inputQueue.poll();
                if (observation == null) {
                    break;
                }
                processObservationSafely(observation);
            }
        } finally {
            drainScheduled.set(false);
            synchronized (lifecycleLock) {
                if (state == State.ACTIVE && !inputQueue.isEmpty()) {
                    scheduleDrainLocked();
                }
            }
        }
    }

    private void drainAll() {
        TagObservation observation;
        while ((observation = inputQueue.poll()) != null) {
            processObservationSafely(observation);
        }
    }

    private void processObservationSafely(TagObservation observation) {
        try {
            processObservation(observation);
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Could not process tag observation {}",
                    observation.tagId().value(),
                    ex);
        }
    }

    private void processObservation(TagObservation observation) {
        LOG.trace(
                "Tag observation: tagId={}, rssi={}, observedAt={}",
                observation.tagId().value(),
                observation.rssi(),
                observation.observedAt());

        RegistrationId registrationId = eventData.registrationIdFor(observation.tagId());
        if (registrationId == null) {
            metrics.recordUnmapped();
            LOG.debug(
                    "Tag observation ignored because TagId {} is unmapped",
                    observation.tagId().value());
            return;
        }

        metrics.recordMapped();
        if (duplicateFilter.isDuplicate(registrationId)) {
            metrics.recordDuplicate();
            LOG.debug(
                    "Tag observation suppressed as duplicate: registrationId={}, tagId={}",
                    registrationId.value(),
                    observation.tagId().value());
            return;
        }

        observationFilter.add(registrationId, observation);
        publishPassageSnapshots();
        ensureHousekeeping();
    }

    private void ensureHousekeeping() {
        if (!hasTimedState()) {
            return;
        }

        synchronized (lifecycleLock) {
            if (state != State.ACTIVE || housekeepingTask != null) {
                return;
            }
            housekeepingTask = scheduler.scheduleWithFixedDelay(
                    this::housekeeping,
                    policyConfiguration.currentValue().sweepCadenceNanos());
        }
    }

    /**
     * Composition-wired callback for runtime tag-processing policy changes.
     *
     * <p>TimingNode wires this callback once while constructing its TagProcessor
     * child. The event graph is not changed during component activation or
     * deactivation.</p>
     */
    public void onPolicyConfigurationChanged(
            ConfigurationChange<TagProcessingPolicy> change) {
        synchronized (lifecycleLock) {
            if (state != State.ACTIVE) {
                return;
            }
            if (!trySubmitToTagLane(() -> applyPolicyChange(change))) {
                LOG.warn("Could not queue TagProcessor policy change");
            }
        }
    }

    private void applyPolicyChange(
            ConfigurationChange<TagProcessingPolicy> change) {
        duplicateFilter.onPolicyChanged();

        if (change.previousValue().sweepCadenceNanos()
                != change.currentValue().sweepCadenceNanos()) {
            synchronized (lifecycleLock) {
                closeHousekeepingLocked();
            }
        }

        /*
         * Evaluate existing timed state immediately against the new policy.
         * This makes shorter quiet/max/duplicate windows effective without
         * waiting for the next old housekeeping interval.
         */
        housekeeping();
        ensureHousekeeping();
    }

    private void housekeeping() {
        try {
            observationFilter.periodic();
            duplicateFilter.periodic();
            publishPassageSnapshots();
        } catch (RuntimeException ex) {
            LOG.warn("Tag-processing housekeeping failed", ex);
        } finally {
            synchronized (lifecycleLock) {
                if (housekeepingTask != null
                        && (state != State.ACTIVE || !hasTimedState())) {
                    closeHousekeepingLocked();
                }
            }
        }
    }

    private void publishPassageSnapshots() {
        List<TagPassageSnapshot> current =
                observationFilter.snapshots();
        passageSnapshots =
                current.isEmpty()
                        ? Collections.<TagPassageSnapshot>emptyList()
                        : Collections.unmodifiableList(
                                new ArrayList<TagPassageSnapshot>(
                                        current));
    }

    private boolean hasTimedState() {
        return observationFilter.hasPendingState()
                || duplicateFilter.hasPendingState();
    }

    private void closeHousekeepingLocked() {
        LaneScheduler.ScheduledRegistration task = housekeepingTask;
        housekeepingTask = null;
        if (task != null) {
            task.close();
        }
    }

    /**
     * Tries to queue one fire-and-forget operation on the TagProcessor lane.
     *
     * <p>Returns {@code true} only when the work was queued for later serial
     * execution; it does not report the eventual operation outcome.</p>
     */
    private boolean trySubmitToTagLane(
            Runnable operation) {
        return lane.offer(operation)
                == SerialLane.AdmissionResult.ACCEPTED;
    }

    private void processValidObservation(
            RegistrationId registrationId,
            TimingTimestamp observedAt) {
        duplicateFilter.recordRegistration(
                registrationId);

        /*
         * The observation that closed the previous passage may already have
         * started a next passage. The selected registration now owns the
         * duplicate window, so that next passage can be discarded locally.
         */
        observationFilter.discard(
                registrationId);
        publishPassageSnapshots();

        newRegistrationEvent.emit(
                new NewRegistration(
                        registrationId,
                        observedAt));
    }

    /** Immutable registration selected from one completed tag passage. */
    public static final class NewRegistration {
        private final RegistrationId registrationId;
        private final TimingTimestamp observedAt;

        public NewRegistration(
                RegistrationId registrationId,
                TimingTimestamp observedAt) {
            if (registrationId == null) {
                throw new IllegalArgumentException(
                        "registrationId must not be null");
            }
            if (observedAt == null) {
                throw new IllegalArgumentException(
                        "observedAt must not be null");
            }
            this.registrationId = registrationId;
            this.observedAt = observedAt;
        }

        public RegistrationId registrationId() {
            return registrationId;
        }

        public TimingTimestamp observedAt() {
            return observedAt;
        }
    }
}
