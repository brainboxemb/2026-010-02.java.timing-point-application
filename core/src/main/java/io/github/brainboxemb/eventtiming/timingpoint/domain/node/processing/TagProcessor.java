package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ConfigurationChange;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.ScheduledSerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Active tag-processing boundary between antenna observations and registrations.
 *
 * <p>The antenna callback only performs bounded queue ingress. EventData resolution,
 * duplicate suppression and passage selection run on one serial lane owned by
 * this processor. A completed passage is published as a {@link NewRegistration};
 * TagProcessor does not know where that registration is stored.</p>
 */
public final class TagProcessor extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(TagProcessor.class);

    /** Runtime-policy changes consumed by this processor. */
    private static final EventInput<TagProcessor, ConfigurationChange<TagProcessingPolicy>> POLICY_CHANGED =
            EventInput.of(
                    (processor, source) -> source.subscribe(processor::onPolicyConfigurationChanged));

    private final EventData eventData;
    private final Event<NewRegistration> newRegistration = new Event<NewRegistration>();

    /** Selected-registration source published after passage filtering. */
    public final EventSource<NewRegistration> NEW_REGISTRATION = newRegistration;
    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final RegistrationDuplicateFilter duplicateFilter;
    private final TagProcessingMetrics metrics;
    private final TagObservationFilter observationFilter;
    private final ScheduledSerialLane lane;
    private final ArrayBlockingQueue<TagObservation> inputQueue;
    private final Object lifecycleLock = new Object();

    private boolean observationProcessingScheduled;

    private volatile ScheduledSerialLane.ScheduledRegistration observationFilterTask;
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
        failIfInvalidArgument(eventData != null, "eventData must not be null");
        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");
        failIfInvalidArgument(lane != null, "lane must not be null");

        TagProcessingPolicy startupPolicy = policyConfiguration.startupValue();
        failIfInvalidArgument(startupPolicy != null, "policyConfiguration.startupValue must not be null");

        this.eventData = eventData;
        this.policyConfiguration = policyConfiguration;
        this.metrics = metrics;
        this.lane = lane;
        this.inputQueue = new ArrayBlockingQueue<>(startupPolicy.observationQueueCapacity());
        duplicateFilter = new RegistrationDuplicateFilter(policyConfiguration, monotonicClock);
        observationFilter = new TagObservationFilter(
                policyConfiguration, monotonicClock, metrics, this::processValidObservation);

        POLICY_CHANGED.bind(this, policyConfiguration.changes());
    }

    /**
     * Starts the TagProcessor lane and allows antenna observations to be queued.
     */
    @Override
    protected void onActivate() {
        lane.start();
    }

    /**
     * Stops new observation intake, drains already queued local observations
     * when possible, and closes the TagProcessor lane.
     */
    @Override
    protected void onDeactivate() {
        synchronized (lifecycleLock) {
            cancelObservationFilterLocked();
            cancelHousekeepingLocked();
            if (!inputQueue.isEmpty()) {
                try {
                    lane.execute(this::processQueuedObservations);
                } catch (RejectedExecutionException ex) {
                    inputQueue.clear();
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
        failIfInvalidArgument(observation != null, "observation must not be null");

        metrics.recordObservation();

        synchronized (lifecycleLock) {
            if (!isActive()) {
                metrics.recordProcessorNotRunning();
            } else if (!inputQueue.offer(observation)) {
                metrics.recordObservationQueueFull();
            } else {
                scheduleObservationProcessingLocked();
            }
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

    /** Schedules queued-observation processing when no run is already pending. */
    private void scheduleObservationProcessingLocked() {
        if (observationProcessingScheduled) {
            return;
        }

        observationProcessingScheduled = true;
        try {
            lane.execute(this::processQueuedObservations);
        } catch (RejectedExecutionException ex) {
            observationProcessingScheduled = false;
            metrics.recordProcessorNotRunning();
        }
    }

    /**
     * Processes the observations that were queued when this processing run started.
     *
     * <p>Observations arriving during the run remain in the bounded ingress queue
     * and cause one follow-up run.</p>
     */
    private void processQueuedObservations() {
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
            synchronized (lifecycleLock) {
                observationProcessingScheduled = false;
                if (isActive() && !inputQueue.isEmpty()) {
                    scheduleObservationProcessingLocked();
                }
            }
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
                ensureObservationFilter();
                ensureHousekeeping();
            }
        }
    }

    /** Ensures periodic passage-filter evaluation while open passage state exists. */
    private void ensureObservationFilter() {
        if (!observationFilter.hasPendingState()) {
            return;
        }

        synchronized (lifecycleLock) {
            if (!isActive() || observationFilterTask != null) {
                return;
            }
            observationFilterTask = lane.scheduleWithFixedDelay(
                    this::processObservationFilter,
                    policyConfiguration.currentValue().sweepCadenceNanos());
        }
    }

    /** Ensures periodic duplicate-state housekeeping while remembered registrations exist. */
    private void ensureHousekeeping() {
        if (!duplicateFilter.hasPendingState()) {
            return;
        }

        synchronized (lifecycleLock) {
            if (!isActive() || housekeepingTask != null) {
                return;
            }
            housekeepingTask = lane.scheduleWithFixedDelay(
                    this::processHousekeeping,
                    policyConfiguration.currentValue().sweepCadenceNanos());
        }
    }

    /** Queues one policy change from the processor-owned configuration binding. */
    private void onPolicyConfigurationChanged(ConfigurationChange<TagProcessingPolicy> change) {
        synchronized (lifecycleLock) {
            if (!isActive()) {
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
                cancelObservationFilterLocked();
                cancelHousekeepingLocked();
            }
        }

        /*
         * Apply the new time windows immediately rather than waiting for the
         * next scheduled filter/housekeeping run.
         */
        processObservationFilter();
        processHousekeeping();
        ensureObservationFilter();
        ensureHousekeeping();
    }

    /** Processes one scheduled passage-filter evaluation run. */
    private void processObservationFilter() {
        try {
            observationFilter.periodic();
            publishPassageSnapshots();
        } catch (RuntimeException ex) {
            LOG.warn("Tag observation filter processing failed", ex);
        } finally {
            synchronized (lifecycleLock) {
                if (observationFilterTask != null
                        && (!isActive() || !observationFilter.hasPendingState())) {
                    cancelObservationFilterLocked();
                }
            }
        }
    }

    /** Processes one scheduled duplicate-state housekeeping run. */
    private void processHousekeeping() {
        try {
            duplicateFilter.periodic();
        } catch (RuntimeException ex) {
            LOG.warn("Tag-processing housekeeping failed", ex);
        } finally {
            synchronized (lifecycleLock) {
                if (housekeepingTask != null
                        && (!isActive() || !duplicateFilter.hasPendingState())) {
                    cancelHousekeepingLocked();
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

    /** Cancels the current fixed-delay passage-filter registration while holding the lifecycle lock. */
    private void cancelObservationFilterLocked() {
        ScheduledSerialLane.ScheduledRegistration task = observationFilterTask;
        observationFilterTask = null;
        if (task != null) {
            task.close();
        }
    }

    /** Cancels the current fixed-delay housekeeping registration while holding the lifecycle lock. */
    private void cancelHousekeepingLocked() {
        ScheduledSerialLane.ScheduledRegistration task = housekeepingTask;
        housekeepingTask = null;
        if (task != null) {
            task.close();
        }
    }

    /** Converts one filter-approved observation into a registration event. */
    private void processValidObservation(RegistrationId registrationId, TimingTimestamp observedAt) {
        duplicateFilter.recordRegistration(registrationId);
        ensureHousekeeping();

        /*
         * The observation that closed the previous passage may already have
         * started a next passage. The selected registration now owns the
         * duplicate window, so that next passage can be discarded locally.
         */
        observationFilter.discard(registrationId);
        publishPassageSnapshots();

        newRegistration.emit(new NewRegistration(registrationId, observedAt));
    }

    /** Immutable registration selected from one completed tag passage. */
    public static final class NewRegistration {
        private final RegistrationId registrationId;
        private final TimingTimestamp observedAt;

        /** Creates the immutable registration fact emitted after passage selection. */
        public NewRegistration(RegistrationId registrationId, TimingTimestamp observedAt) {
            failIfInvalidArgument(registrationId != null, "registrationId must not be null");
            failIfInvalidArgument(observedAt != null, "observedAt must not be null");
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
