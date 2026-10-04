package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;

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
        return process(observation.tagId(), observation.time());
    }

    /** Convenience form used by deterministic simulation/tests. */
    public ObservationResult process(String tagId, TimingTimestamp time) {
        TagId source = new TagId(tagId);
        if (time == null) {
            throw new IllegalArgumentException("time must not be null");
        }

        RegistrationId registrationId = resolver.resolve(source);
        if (registrationId == null) {
            return ObservationResult.UNKNOWN_TAG;
        }

        CommandAdmission admission =
                timingNode.submit(
                        TimingNodeCommands.addAutomaticRegistration(
                                registrationId,
                                time));
        switch (admission) {
            case ACCEPTED:
                return ObservationResult.ADMITTED;
            case FULL:
                return ObservationResult.QUEUE_FULL;
            case NOT_RUNNING:
                return ObservationResult.NODE_NOT_RUNNING;
            default:
                throw new IllegalStateException(
                        "Unsupported TimingNode admission result " + admission);
        }
    }
}
