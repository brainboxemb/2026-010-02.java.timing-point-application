package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;

import java.util.concurrent.atomic.AtomicLong;

/**
 * TimingNode-local processing of decoded antenna observations.
 *
 * <p>TagProcessor resolves source identity and submits accepted semantic work to
 * the TimingNode. It does not allocate TimingData sequence/location context,
 * mutate TimingNode state directly or persist records.</p>
 */
public final class TagProcessor {

    /** Immediate outcome of processing one decoded observation. */
    public enum ObservationResult {
        /** The resulting registration command entered the bounded TimingNode lane. */
        ADMITTED,
        /** No RegistrationId exists for this TagId in current local reference data. */
        UNKNOWN_TAG,
        /** The bounded TimingNode lane was full. */
        QUEUE_FULL,
        /** The TimingNode serial lane was not running. */
        NODE_NOT_RUNNING
    }

    private final TimingNode timingNode;
    private final TagRegistrationResolver resolver;
    private final AtomicLong observationCount = new AtomicLong();
    private final AtomicLong resolvedCount = new AtomicLong();
    private final AtomicLong unknownTagCount = new AtomicLong();
    private final AtomicLong admittedCount = new AtomicLong();
    private final AtomicLong queueFullCount = new AtomicLong();
    private final AtomicLong nodeNotRunningCount = new AtomicLong();

    public TagProcessor(
            TimingNode timingNode,
            TagRegistrationResolver resolver) {
        if (timingNode == null) {
            throw new IllegalArgumentException("timingNode must not be null");
        }
        if (resolver == null) {
            throw new IllegalArgumentException("resolver must not be null");
        }
        this.timingNode = timingNode;
        this.resolver = resolver;
    }

    /** Antenna callback entry point. */
    public void onObservation(Antenna.Observation observation) {
        process(observation);
    }

    /** Processes one decoded observation and returns only its immediate ingress result. */
    public ObservationResult process(Antenna.Observation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("observation must not be null");
        }

        observationCount.incrementAndGet();
        TagId source = new TagId(observation.tagId());
        TimingTimestamp time = observation.time();
        RegistrationId registrationId = resolver.resolve(source);
        if (registrationId == null) {
            unknownTagCount.incrementAndGet();
            return ObservationResult.UNKNOWN_TAG;
        }

        resolvedCount.incrementAndGet();
        CommandAdmission admission =
                timingNode.submit(
                        TimingNodeCommands.addAutomaticRegistration(
                                registrationId,
                                time));
        switch (admission) {
            case ACCEPTED:
                admittedCount.incrementAndGet();
                return ObservationResult.ADMITTED;
            case FULL:
                queueFullCount.incrementAndGet();
                return ObservationResult.QUEUE_FULL;
            case NOT_RUNNING:
                nodeNotRunningCount.incrementAndGet();
                return ObservationResult.NODE_NOT_RUNNING;
            default:
                throw new IllegalStateException(
                        "Unsupported TimingNode admission result " + admission);
        }
    }

    /** Returns decoded observations presented to this processor. */
    public long observationCount() {
        return observationCount.get();
    }

    /** Returns observations whose TagId resolved to a RegistrationId. */
    public long resolvedCount() {
        return resolvedCount.get();
    }

    /** Returns observations filtered because their TagId was unknown. */
    public long unknownTagCount() {
        return unknownTagCount.get();
    }

    /** Returns resolved observations admitted to the TimingNode lane. */
    public long admittedCount() {
        return admittedCount.get();
    }

    /** Returns resolved observations rejected because the TimingNode queue was full. */
    public long queueFullCount() {
        return queueFullCount.get();
    }

    /** Returns resolved observations rejected because the TimingNode was not running. */
    public long nodeNotRunningCount() {
        return nodeNotRunningCount.get();
    }
}
