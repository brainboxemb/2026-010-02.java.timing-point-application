package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.PeriodicExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.PeriodicTask;

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
    private final PeriodicExecutor periodicExecutor;
    private final long sweepCadenceNanos;

    private PeriodicTask periodicTask;

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
            TagProcessingCounters counters,
            PeriodicExecutor periodicExecutor) {
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
        if (periodicExecutor == null) {
            throw new IllegalArgumentException("periodicExecutor must not be null");
        }

        this.timingNode = timingNode;
        this.mapper = mapper;
        this.counters = counters;
        this.periodicExecutor = periodicExecutor;
        this.sweepCadenceNanos = policy.sweepCadenceNanos();
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
     * Starts the periodic processing registration.
     *
     * <p>Observation callbacks themselves stay on the antenna/provider caller
     * thread. Only housekeeping is scheduled through PeriodicExecutor.</p>
     */
    public synchronized void start() {
        if (periodicTask != null) {
            throw new IllegalStateException("TagProcessor is already started");
        }
        periodicTask = periodicExecutor.scheduleWithFixedDelay(
                this::periodic,
                sweepCadenceNanos);
    }

    /**
     * Stops this processor's periodic registration.
     *
     * <p>The PeriodicExecutor may be shared and is therefore not closed here.</p>
     */
    public synchronized void stop() {
        if (periodicTask == null) {
            return;
        }
        periodicTask.close();
        periodicTask = null;
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
     * Runs one periodic processing pass.
     *
     * <p>The execution model decides when this is called. TagProcessor owns the
     * processing components and delegates their periodic housekeeping here.</p>
     */
    public void periodic() {
        observationFilter.periodic();
        duplicateFilter.periodic();
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
