package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ConfigurationChange;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.ScheduledSerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

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
    private final Event<NewRegistration> newRegistrationEvent = new Event<NewRegistration>();
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final RegistrationDuplicateFilter duplicateFilter;
    private final TagProcessingMetrics metrics;
    private final TagObservationFilter observationFilter;
    private final ScheduledSerialLane lane;
    private final ArrayBlockingQueue<TagObservation> inputQueue;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final Object lifecycleLock = new Object();

    private volatile State state = State.NEW;
    private volatile ScheduledSerialLane.ScheduledRegistration housekeepingTask;
    private volatile List<TagPassageSnapshot> passageSnapshots = Collections.emptyList();

    /** Creates a TagProcessor with one fixed startup policy. */
    public TagProcessor(
            EventData eventData,
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            ScheduledSerialLane lane) {
        this(eventData, ReadOnlyConfiguration.fixed(policy), monotonicClock, metrics, lane);
    }

    /** Creates a TagProcessor backed by the supplied live read-only policy configuration. */
    public TagProcessor(
            EventData eventData,
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            ScheduledSerialLane lane) {
        checkArgument(eventData != null, "eventData must not be null");
        checkArgument(policyConfiguration != null, "policyConfiguration must not be null");
        checkArgument(monotonicClock != null, "monotonicClock must not be null");
        checkArgument(metrics != null, "metrics must not be null");
        checkArgument(lane != null, "lane must not be null");

        TagProcessingPolicy startupPolicy = policyConfiguration.startupValue();
        checkArgument(startupPolicy != null, "policyConfiguration.startupValue must not be null");

        this.eventData = eventData;
        this.policyConfiguration = policyConfiguration;
        this.metrics = metrics;
        this.lane = lane;
        this.inputQueue = new ArrayBlockingQueue<>(startupPolicy.observationQueueCapacity());
        duplicateFilter = new RegistrationDuplicateFilter(policyConfiguration, monotonicClock);
        observationFilter = new TagObservationFilter(
                policyConfiguration, monotonicClock, metrics, this::processValidObservation);

        policyConfiguration.changes().subscribe(this::onPolicyConfigurationChanged);
    }

    /**
     * Starts the TagProcessor lane and allows antenna observations to be queued.
     */
    public void activate() {
        synchronized (lifecycleLock) {
            checkState(state != State.ACTIVE, "TagProcessor is already active");
            lane.start();
            state = State.ACTIVE;
        }
    }

    /**
     * Stops new observation intake, drains already queued local observations
     * when possible, and closes the TagProcessor lane.
     */
    public void deactivate() {
        synchronized (lifecycleLock) {
            if (state == State.INACTIVE) {
                return;
            }

            if (state == State.NEW) {
                state = State.INACTIVE;
            } else {
                state = State.INACTIVE;
                closeHousekeepingLocked();
                if (!inputQueue.isEmpty()) {
                    try {
                        lane.execute(this::drainAll);
                    } catch (RejectedExecutionException ex) {
                        inputQueue.clear();
                    }
                }
            }
        }

        lane.close();
        inputQueue.clear();
    }

    /**
     * Accepts one antenna observation into the bounded processor ingress queue.
     *
     * <p>The callback only records/queues the immutable observation and returns.
     * Mapping, duplicate checks and passage selection run later on the
     * TagProcessor lane.</p>
     */
    public void onTagObserved(TagObservation observation) {
        checkArgument(observation != null, "observation must not be null");

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
     * Returns the latest immutable engineering snapshot of active tag passages.
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

    /** Schedules one TagProcessor-lane drain when no drain turn is already pending. */
    private void scheduleDrainLocked() {
        if (!drainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            lane.execute(this::drainBatch);
        } catch (RejectedExecutionException ex) {
            drainScheduled.set(false);
            metrics.recordProcessorNotRunning();
        }
    }

    /** Processes the observations that were queued when this lane turn started. */
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

    /** Processes all remaining observations during orderly deactivation. */
    private void drainAll() {
        TagObservation observation;
        while ((observation = inputQueue.poll()) != null) {
            processObservationSafely(observation);
        }
    }

    /** Contains one observation-processing failure so later antenna observations can continue. */
    private void processObservationSafely(TagObservation observation) {
        try {
            processObservation(observation);
        } catch (RuntimeException ex) {
            LOG.warn("Could not process tag observation {}", observation.tagId().value(), ex);
        }
    }

    /** Maps and filters one queued antenna observation on the TagProcessor lane. */
    private void processObservation(TagObservation observation) {
        LOG.trace(
                "Tag observation: tagId={}, rssi={}, observedAt={}",
                observation.tagId().value(),
                observation.rssi(),
                observation.observedAt());

        RegistrationId registrationId = eventData.registrationIdFor(observation.tagId());
        if (registrationId == null) {
            metrics.recordUnmapped();
            LOG.debug("Tag observation ignored because TagId {} is unmapped", observation.tagId().value());
        } else {
            metrics.recordMapped();
            if (duplicateFilter.isDuplicate(registrationId)) {
                metrics.recordDuplicate();
                LOG.debug(
                        "Tag observation suppressed as duplicate: registrationId={}, tagId={}",
                        registrationId.value(),
                        observation.tagId().value());
            } else {
                observationFilter.add(registrationId, observation);
                publishPassageSnapshots();
                ensureHousekeeping();
            }
        }
    }

    /** Ensures one periodic housekeeping registration exists while timed filter state is active. */
    private void ensureHousekeeping() {
        if (!hasTimedState()) {
            return;
        }

        synchronized (lifecycleLock) {
            if (state != State.ACTIVE || housekeepingTask != null) {
                return;
            }
            housekeepingTask = lane.scheduleWithFixedDelay(
                    this::housekeeping,
                    policyConfiguration.currentValue().sweepCadenceNanos());
        }
    }

    /** Queues one policy change from the processor-owned configuration binding. */
    private void onPolicyConfigurationChanged(ConfigurationChange<TagProcessingPolicy> change) {
        synchronized (lifecycleLock) {
            if (state != State.ACTIVE) {
                return;
            }
            Runnable policyChange = () -> applyPolicyChange(change);
            try {
                lane.execute(policyChange);
            } catch (RejectedExecutionException ex) {
                LOG.warn("Could not queue TagProcessor policy change", ex);
            }
        }
    }

    /** Applies one accepted tag-processing policy change on the owning serial lane. */
    private void applyPolicyChange(ConfigurationChange<TagProcessingPolicy> change) {
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

    /** Advances timeout-based tag filtering and republishes the engineering snapshot. */
    private void housekeeping() {
        try {
            observationFilter.periodic();
            duplicateFilter.periodic();
            publishPassageSnapshots();
        } catch (RuntimeException ex) {
            LOG.warn("Tag-processing housekeeping failed", ex);
        } finally {
            synchronized (lifecycleLock) {
                if (housekeepingTask != null && (state != State.ACTIVE || !hasTimedState())) {
                    closeHousekeepingLocked();
                }
            }
        }
    }

    /** Publishes an immutable engineering snapshot of the current passage-filter state. */
    private void publishPassageSnapshots() {
        List<TagPassageSnapshot> current = observationFilter.snapshots();
        passageSnapshots =
                current.isEmpty()
                        ? Collections.<TagPassageSnapshot>emptyList()
                        : Collections.unmodifiableList(
                                new ArrayList<TagPassageSnapshot>(
                                        current));
    }

    /** Returns whether filter state still needs periodic timeout housekeeping. */
    private boolean hasTimedState() {
        return observationFilter.hasPendingState() || duplicateFilter.hasPendingState();
    }

    /** Cancels the current housekeeping registration while holding the lifecycle lock. */
    private void closeHousekeepingLocked() {
        LaneScheduler.ScheduledRegistration task = housekeepingTask;
        housekeepingTask = null;
        if (task != null) {
            task.close();
        }
    }

    /** Converts one filter-approved observation into a registration event. */
    private void processValidObservation(RegistrationId registrationId, TimingTimestamp observedAt) {
        duplicateFilter.recordRegistration(registrationId);

        /*
         * The observation that closed the previous passage may already have
         * started a next passage. The selected registration now owns the
         * duplicate window, so that next passage can be discarded locally.
         */
        observationFilter.discard(registrationId);
        publishPassageSnapshots();

        newRegistrationEvent.emit(new NewRegistration(registrationId, observedAt));
    }

    /** Immutable registration selected from one completed tag passage. */
    public static final class NewRegistration {
        private final RegistrationId registrationId;
        private final TimingTimestamp observedAt;

        /** Creates the immutable registration fact emitted after passage selection. */
        public NewRegistration(RegistrationId registrationId, TimingTimestamp observedAt) {
            checkArgument(registrationId != null, "registrationId must not be null");
            checkArgument(observedAt != null, "observedAt must not be null");
            this.registrationId = registrationId;
            this.observedAt = observedAt;
        }

        /** Returns the registration selected from the completed tag passage. */
        public RegistrationId registrationId() {
            return registrationId;
        }

        /** Returns the observation time retained for the automatic registration. */
        public TimingTimestamp observedAt() {
            return observedAt;
        }
    }
}
