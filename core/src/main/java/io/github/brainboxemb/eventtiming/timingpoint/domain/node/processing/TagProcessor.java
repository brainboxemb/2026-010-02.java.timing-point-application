package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ConfigurationChange;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Active tag-processing boundary between antenna observations and registrations.
 *
 * <p>Antenna observations use one bounded typed {@link SerialLane}. Periodic
 * filter and housekeeping work use the supplied JDK
 * {@link ScheduledExecutorService} directly. Runtime supplies one single-thread
 * executor for both, so TagProcessor state is never processed concurrently and
 * no second scheduled-lane abstraction is needed.</p>
 */
public final class TagProcessor extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(TagProcessor.class);

    /** Runtime-policy changes consumed by this processor. */
    private static final EventInput<TagProcessor, ConfigurationChange<TagProcessingPolicy>>
            POLICY_CHANGED =
                    EventInput.of(
                            (processor, source) ->
                                    source.subscribe(processor::onPolicyConfigurationChanged));

    private final EventData eventData;
    private final Event<NewRegistration> newRegistration =
            new Event<NewRegistration>();

    /** Selected-registration source published after passage filtering. */
    public final EventSource<NewRegistration> NEW_REGISTRATION = newRegistration;

    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final RegistrationDuplicateFilter duplicateFilter;
    private final TagProcessingMetrics metrics;
    private final TagObservationFilter observationFilter;
    private final SerialLane<TagObservation> observations;
    private final ScheduledExecutorService scheduler;
    private final Object lifecycleLock = new Object();

    private ScheduledFuture<?> observationFilterTask;
    private ScheduledFuture<?> housekeepingTask;
    private volatile List<TagPassageSnapshot> passageSnapshots =
            Collections.emptyList();

    /** Creates a TagProcessor with one fixed startup policy. */
    public TagProcessor(
            EventData eventData,
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            ScheduledExecutorService scheduler) {

        this(
                eventData,
                ReadOnlyConfiguration.fixed(policy),
                monotonicClock,
                metrics,
                scheduler);
    }

    /** Creates a TagProcessor backed by the supplied live read-only policy configuration. */
    public TagProcessor(
            EventData eventData,
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            ScheduledExecutorService scheduler) {

        failIfInvalidArgument(eventData != null, "eventData must not be null");
        failIfInvalidArgument(
                policyConfiguration != null,
                "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");

        TagProcessingPolicy startupPolicy = policyConfiguration.startupValue();
        failIfInvalidArgument(
                startupPolicy != null,
                "policyConfiguration.startupValue must not be null");

        this.eventData = eventData;
        this.policyConfiguration = policyConfiguration;
        this.metrics = metrics;
        this.scheduler = scheduler;
        observations =
                new SerialLane<TagObservation>(
                        startupPolicy.observationQueueCapacity(),
                        startupPolicy.observationQueueCapacity(),
                        "TagProcessor",
                        scheduler,
                        this::processObservationSafely);
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

        POLICY_CHANGED.bind(this, policyConfiguration.changes());
    }

    /** Starts bounded observation admission. */
    @Override
    protected void onActivate() {
        observations.start();
    }

    /** Cancels periodic work and drains already accepted observations. */
    @Override
    protected void onDeactivate() {
        synchronized (lifecycleLock) {
            cancelObservationFilterLocked();
            cancelHousekeepingLocked();
        }
        observations.close();
    }

    /** Queues one immutable antenna observation without blocking the callback thread. */
    public void onTagObserved(TagObservation observation) {
        failIfInvalidArgument(observation != null, "observation must not be null");

        metrics.recordObservation();

        if (!isActive()) {
            metrics.recordProcessorNotRunning();
            return;
        }

        final SerialLane.OfferResult result;
        try {
            result = observations.offer(observation);
        } catch (RejectedExecutionException ex) {
            metrics.recordProcessorNotRunning();
            LOG.warn("Could not schedule TagProcessor observation worker", ex);
            return;
        }

        switch (result) {
            case ACCEPTED:
                break;
            case FULL:
                metrics.recordObservationQueueFull();
                break;
            case NOT_RUNNING:
                metrics.recordProcessorNotRunning();
                break;
            default:
                throw new IllegalStateException(
                        "Unsupported observation queue result " + result);
        }
    }

    /** Returns the latest immutable engineering snapshot of active tag passages. */
    public List<TagPassageSnapshot> passageSnapshots() {
        return passageSnapshots;
    }

    /** Contains one observation failure so later observations continue. */
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

    /** Maps and filters one queued antenna observation. */
    private void processObservation(TagObservation observation) {
        LOG.trace(
                "Tag observation: tagId={}, rssi={}, observedAt={}",
                observation.tagId().value(),
                observation.rssi(),
                observation.observedAt());

        RegistrationId registrationId =
                eventData.registrationIdFor(observation.tagId());
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
        ensureObservationFilter();
    }

    /** Starts the JDK fixed-delay filter task while passage state exists. */
    private void ensureObservationFilter() {
        if (!observationFilter.hasPendingState()) {
            return;
        }

        synchronized (lifecycleLock) {
            if (!isActive() || observationFilterTask != null) {
                return;
            }

            long cadenceNanos =
                    policyConfiguration.currentValue().sweepCadenceNanos();
            observationFilterTask =
                    scheduler.scheduleWithFixedDelay(
                            this::processObservationFilter,
                            cadenceNanos,
                            cadenceNanos,
                            TimeUnit.NANOSECONDS);
        }
    }

    /** Starts the JDK fixed-delay duplicate housekeeping while state exists. */
    private void ensureHousekeeping() {
        if (!duplicateFilter.hasPendingState()) {
            return;
        }

        synchronized (lifecycleLock) {
            if (!isActive() || housekeepingTask != null) {
                return;
            }

            long cadenceNanos =
                    policyConfiguration.currentValue().sweepCadenceNanos();
            housekeepingTask =
                    scheduler.scheduleWithFixedDelay(
                            this::processHousekeeping,
                            cadenceNanos,
                            cadenceNanos,
                            TimeUnit.NANOSECONDS);
        }
    }

    /** Serializes a policy change on the same single-thread scheduler. */
    private void onPolicyConfigurationChanged(
            ConfigurationChange<TagProcessingPolicy> change) {

        synchronized (lifecycleLock) {
            if (!isActive()) {
                return;
            }
        }

        try {
            scheduler.execute(
                    () -> applyPolicyChange(change));
        } catch (RejectedExecutionException ex) {
            LOG.warn("Could not schedule TagProcessor policy change", ex);
        }
    }

    private void applyPolicyChange(
            ConfigurationChange<TagProcessingPolicy> change) {

        synchronized (lifecycleLock) {
            if (!isActive()) {
                return;
            }

            duplicateFilter.onPolicyChanged();

            if (change.previousValue().sweepCadenceNanos()
                    != change.currentValue().sweepCadenceNanos()) {
                cancelObservationFilterLocked();
                cancelHousekeepingLocked();
            }

            processObservationFilterLocked();
            processHousekeepingLocked();
            ensureObservationFilter();
            ensureHousekeeping();
        }
    }

    /** Executes one scheduled filter sweep on the shared TagProcessor thread. */
    private void processObservationFilter() {
        synchronized (lifecycleLock) {
            if (!isActive()) {
                return;
            }
            processObservationFilterLocked();
        }
    }

    private void processObservationFilterLocked() {
        try {
            observationFilter.periodic();
            publishPassageSnapshots();
        } catch (RuntimeException ex) {
            LOG.warn("Tag observation filter processing failed", ex);
        }

        if (!observationFilter.hasPendingState()) {
            cancelObservationFilterLocked();
        }
    }

    /** Executes one scheduled duplicate-state housekeeping pass. */
    private void processHousekeeping() {
        synchronized (lifecycleLock) {
            if (!isActive()) {
                return;
            }
            processHousekeepingLocked();
        }
    }

    private void processHousekeepingLocked() {
        try {
            duplicateFilter.periodic();
        } catch (RuntimeException ex) {
            LOG.warn("Tag-processing housekeeping failed", ex);
        }

        if (!duplicateFilter.hasPendingState()) {
            cancelHousekeepingLocked();
        }
    }

    /** Publishes an immutable engineering snapshot of current passage state. */
    private void publishPassageSnapshots() {
        List<TagPassageSnapshot> current = observationFilter.snapshots();
        passageSnapshots =
                current.isEmpty()
                        ? Collections.<TagPassageSnapshot>emptyList()
                        : Collections.unmodifiableList(
                                new ArrayList<TagPassageSnapshot>(current));
    }

    private void cancelObservationFilterLocked() {
        ScheduledFuture<?> task = observationFilterTask;
        observationFilterTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void cancelHousekeepingLocked() {
        ScheduledFuture<?> task = housekeepingTask;
        housekeepingTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    /** Converts one filter-approved observation into a registration event. */
    private void processValidObservation(
            RegistrationId registrationId,
            TimingTimestamp observedAt) {

        duplicateFilter.recordRegistration(registrationId);
        ensureHousekeeping();

        /*
         * The observation that closed the previous passage may already have
         * started a next passage. The selected registration now owns the
         * duplicate window, so that next passage can be discarded locally.
         */
        observationFilter.discard(registrationId);
        publishPassageSnapshots();

        newRegistration.emit(
                new NewRegistration(
                        registrationId,
                        observedAt));
    }

    /** Immutable registration selected from one completed tag passage. */
    public static final class NewRegistration {
        private final RegistrationId registrationId;
        private final TimingTimestamp observedAt;

        /** Creates the immutable registration fact emitted after passage selection. */
        public NewRegistration(
                RegistrationId registrationId,
                TimingTimestamp observedAt) {

            failIfInvalidArgument(
                    registrationId != null,
                    "registrationId must not be null");
            failIfInvalidArgument(
                    observedAt != null,
                    "observedAt must not be null");
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
