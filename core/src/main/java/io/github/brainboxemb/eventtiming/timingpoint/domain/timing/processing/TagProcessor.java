package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Coordinates the steps between decoded antenna observations and TimingNode.
 *
 * <p>The detailed filtering state deliberately lives in focused collaborators:
 * TagObservationFilter owns passage/RSSI selection, RegistrationDuplicateFilter
 * owns the accepted-registration window, and TagProcessingCounters owns the
 * low-allocation counters. This class only defines their processing order.</p>
 */
public final class TagProcessor implements AutoCloseable {
    private final RegistrationSubmitter registrationSubmitter;
    private final TagRegistrationMapper mapper;
    private final RegistrationDuplicateFilter duplicateFilter;
    private final TagProcessingCounters counters;
    private final TagObservationFilter observationFilter;

    private volatile boolean closed;

    public TagProcessor(
            TimingNode timingNode,
            TagRegistrationMapper mapper,
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            ScheduledExecutorService scheduler) {
        this(
                timingNode,
                mapper,
                policy,
                monotonicClock,
                scheduler,
                new TagProcessingCounters());
    }

    /**
     * Creates a processor with an explicitly retained counter set.
     *
     * <p>Runtime/engineering composition may retain the same counter object for
     * pull-based measurement without making counters part of the TimingNode API.</p>
     */
    public TagProcessor(
            TimingNode timingNode,
            TagRegistrationMapper mapper,
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            ScheduledExecutorService scheduler,
            TagProcessingCounters counters) {
        this(
                submitterFor(timingNode),
                mapper,
                policy,
                monotonicClock,
                scheduler,
                counters);
    }

    TagProcessor(
            RegistrationSubmitter registrationSubmitter,
            TagRegistrationMapper mapper,
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            ScheduledExecutorService scheduler,
            TagProcessingCounters counters) {
        if (registrationSubmitter == null) {
            throw new IllegalArgumentException(
                    "registrationSubmitter must not be null");
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
        if (counters == null) {
            throw new IllegalArgumentException("counters must not be null");
        }

        this.registrationSubmitter = registrationSubmitter;
        this.mapper = mapper;
        this.counters = counters;
        duplicateFilter =
                new RegistrationDuplicateFilter(policy, monotonicClock);
        observationFilter =
                new TagObservationFilter(
                        policy,
                        monotonicClock,
                        scheduler,
                        counters,
                        this::processSelectedObservation);
    }

    /** Receives one decoded observation from an Antenna EventSource. */
    public void onObservation(TagObservation observation) {
        if (closed) {
            throw new IllegalStateException("TagProcessor is closed");
        }
        observationFilter.onObservation(observation);
    }

    private void processSelectedObservation(TagObservation observation) {
        if (closed) {
            return;
        }

        RegistrationId registrationId = mapper.map(observation.tagId());
        if (registrationId == null) {
            counters.recordUnmapped();
            return;
        }
        counters.recordMapped();

        RegistrationDuplicateFilter.Result result =
                duplicateFilter.submitIfNew(
                        registrationId,
                        () -> registrationSubmitter.submit(
                                registrationId,
                                observation.observedAt()));

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

    @Override
    public void close() {
        closed = true;
        observationFilter.close();
        duplicateFilter.clear();
    }

    private static RegistrationSubmitter submitterFor(TimingNode timingNode) {
        if (timingNode == null) {
            throw new IllegalArgumentException("timingNode must not be null");
        }
        return (registrationId, observedAt) ->
                timingNode.submit(
                        TimingNodeCommands.addAutomaticRegistration(
                                registrationId,
                                observedAt));
    }

    @FunctionalInterface
    interface RegistrationSubmitter {
        CommandAdmission submit(
                RegistrationId registrationId,
                TimingTimestamp observedAt);
    }
}
