package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Maps antenna observations to registrations and selects one time per passage.
 *
 * <p>Every accepted observation is processed on one bounded serial lane. The
 * TagId is first mapped to a RegistrationId and checked against duplicate
 * suppression. Non-duplicate observations enter {@link TagObservationFilter},
 * which groups them into passages and selects the strongest RSSI observation.
 * {@link FilterJob} closes expired passages; {@link HousekeepingJob} expires
 * duplicate-suppression windows. All three use the same Runtime-owned
 * single-thread scheduler, so passage state is never processed concurrently.</p>
 */
public final class TagProcessor extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(TagProcessor.class);

    private final EventData eventData;
    private final TagProcessingMetrics metrics;
    private final FilterJob filterJob;
    private final HousekeepingJob housekeepingJob;
    private final SerialLane<TagObservation> observations;

    private final Event<NewRegistration> newRegistration = new Event<NewRegistration>();

    /** Selected-registration source published after passage filtering. */
    public final EventSource<NewRegistration> NEW_REGISTRATION = newRegistration;

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

    public TagProcessor(
            EventData eventData,
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics,
            ScheduledExecutorService scheduler) {

        failIfInvalidArgument(eventData != null, "eventData must not be null");
        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");

        TagProcessingPolicy startupPolicy = policyConfiguration.startupValue();
        failIfInvalidArgument(startupPolicy != null, "policyConfiguration.startupValue must not be null");

        this.eventData = eventData;
        this.metrics = metrics;

        filterJob = new FilterJob(
                policyConfiguration,
                monotonicClock,
                metrics,
                scheduler,
                this::processValidObservation);
        housekeepingJob = new HousekeepingJob(
                policyConfiguration,
                monotonicClock,
                metrics,
                scheduler);
        observations =
                new SerialLane<TagObservation>(
                        startupPolicy.observationQueueCapacity(),
                        startupPolicy.observationQueueCapacity(),
                        "TagProcessor",
                        scheduler,
                        this::processObservation);

        policyConfiguration.changes().subscribe(filterJob::policyChanged);
        policyConfiguration.changes().subscribe(housekeepingJob::policyChanged);
    }

    /** Starts both timed jobs and then bounded observation admission. */
    @Override
    protected void onActivate() {
        filterJob.start();
        housekeepingJob.start();
        observations.start();
    }

    /** Stops timed work and then drains observations that were already accepted. */
    @Override
    protected void onDeactivate() {
        filterJob.stop();
        housekeepingJob.stop();
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

        SerialLane.OfferResult result = observations.offer(observation);
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

    public boolean hasPendingPassages() {
        return filterJob.hasPendingPassages();
    }

    /**
     * Applies the processing order for one queued antenna observation.
     *
     * <p>Mapping happens first. Only mapped observations are checked for
     * duplicate suppression, and only mapped non-duplicates may enter passage
     * selection.</p>
     */
    private void processObservation(TagObservation observation) {
        TagId tagId = observation.tagId();
        if (LOG.isTraceEnabled()) {
            LOG.trace(
                    "Tag observation: tagId={}, rssi={}, observedAt={}",
                    tagId,
                    observation.rssi(),
                    observation.observedAt());
        }

        RegistrationId registrationId =
                eventData.registrationIdFor(tagId);

        if (registrationId == null) {
            metrics.recordUnmapped();
            LOG.debug(
                    "Tag observation ignored because TagId {} is unmapped",
                    tagId);
        } else if (housekeepingJob.isDuplicate(registrationId)) {
            metrics.recordMapped();
            metrics.recordDuplicate();
            LOG.debug(
                    "Tag observation suppressed as duplicate: registrationId={}, tagId={}",
                    registrationId,
                    tagId);
        } else {
            metrics.recordMapped();
            filterJob.add(registrationId, observation);
        }
    }

    /**
     * Publishes one completed passage as a registration and starts duplicate suppression.
     *
     * <p>The filter may already have opened the next passage for the same
     * RegistrationId before this callback runs. Starting duplicate suppression
     * makes that just-opened passage irrelevant, so it is discarded before the
     * registration event is emitted.</p>
     */
    private void processValidObservation(RegistrationId registrationId, TimingTimestamp observedAt) {
        housekeepingJob.recordRegistration(registrationId);
        filterJob.discard(registrationId);
        newRegistration.emit(new NewRegistration(registrationId, observedAt));
    }

    /** Immutable registration selected from one completed tag passage. */
    public static final class NewRegistration {
        private final RegistrationId registrationId;
        private final TimingTimestamp observedAt;

        public NewRegistration(RegistrationId registrationId, TimingTimestamp observedAt) {

            failIfInvalidArgument(registrationId != null, "registrationId must not be null");
            failIfInvalidArgument(observedAt != null, "observedAt must not be null");
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
