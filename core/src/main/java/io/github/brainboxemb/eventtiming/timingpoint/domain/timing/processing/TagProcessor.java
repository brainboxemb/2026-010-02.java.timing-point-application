package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

/**
 * Coordinates decoded antenna observations on their way to TimingNode.
 *
 * <p>TagObservationFilter owns passage/RSSI selection,
 * RegistrationDuplicateFilter owns the accepted-registration window, and
 * TagProcessingCounters owns the low-allocation counters. This class only
 * defines the order in which those steps are applied.</p>
 */
public final class TagProcessor {
    private final TimingNode timingNode;
    private final TagRegistrationMapper mapper;
    private final RegistrationDuplicateFilter duplicateFilter;
    private final TagProcessingCounters counters;
    private final TagObservationFilter observationFilter;

    /**
     * Creates one tag-processing path for a TimingNode.
     *
     * <p>The counter owner is injected explicitly so runtime/engineering
     * composition can retain the same instance for pull-based measurement
     * without adding a second TagProcessor constructor or exposing counters
     * through TimingNode.</p>
     */
    public TagProcessor(
            TimingNode timingNode,
            TagRegistrationMapper mapper,
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingCounters counters) {
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
        if (counters == null) {
            throw new IllegalArgumentException("counters must not be null");
        }

        this.timingNode = timingNode;
        this.mapper = mapper;
        this.counters = counters;
        duplicateFilter =
                new RegistrationDuplicateFilter(policy, monotonicClock);
        observationFilter =
                new TagObservationFilter(
                        policy,
                        monotonicClock,
                        counters,
                        this::processValidObservation);
    }

    /**
     * Event callback for decoded antenna observations.
     *
     * <p>The EventSource-facing method stays here. The filter itself receives
     * plain items through its collection-style add operation.</p>
     */
    public void onObservation(TagObservation observation) {
        observationFilter.add(observation);
    }

    /**
     * Runs one expiry pass for observation passages.
     *
     * <p>Package-private because runtime scheduling is owned by
     * TagProcessingExpiryScheduler, not by the filter itself.</p>
     */
    void expireObservations() {
        observationFilter.expire();
    }

    private void processValidObservation(TagObservation observation) {
        RegistrationId registrationId = mapper.map(observation.tagId());
        if (registrationId == null) {
            counters.recordUnmapped();
            return;
        }
        counters.recordMapped();

        RegistrationDuplicateFilter.Result result =
                duplicateFilter.submitIfNew(
                        registrationId,
                        () -> timingNode.submit(
                                TimingNodeCommands.addAutomaticRegistration(
                                        registrationId,
                                        observation.observedAt())));

        switch (result) {
            case DUPLICATE:
                counters.recordDuplicate();
                return;
            case ACCEPTED:
                counters.recordAdmission(CommandAdmission.ACCEPTED);
                return;
            case FULL:
                counters.recordAdmission(CommandAdmission.FULL);
                return;
            case NOT_RUNNING:
                counters.recordAdmission(CommandAdmission.NOT_RUNNING);
                return;
            default:
                throw new IllegalStateException(
                        "Unsupported duplicate-filter result " + result);
        }
    }
}
