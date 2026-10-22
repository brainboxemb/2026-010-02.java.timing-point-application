package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord.ManualTimeSource;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Standard TimingData commit commands executed through the LogBook component.
 *
 * <p>Absolute timing values cross this internal boundary as UTC epoch-millisecond longs.
 * Semantic timestamp wrappers belong at presentation/protocol boundaries, not in queued
 * LogBook commands.</p>
 */
public final class LogBookCommands {
    /** This class is a stateless command factory. */
    private LogBookCommands() {
    }

    /** NODE_OPEN timing is sampled by LogBook from its composed TimeSource. */
    public static LogBookCommand<Void> commitNodeOpen(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        return simple(
                "commitNodeOpen",
                operations -> {
                    operations.commitNodeOpen(locationId);
                    return null;
                });
    }

    /** NODE_CLOSE timing is sampled by LogBook from its composed TimeSource. */
    public static LogBookCommand<Void> commitNodeClose(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        return simple(
                "commitNodeClose",
                operations -> {
                    operations.commitNodeClose(locationId);
                    return null;
                });
    }

    /** Antenna observation time is already absolute when it enters the LogBook command path. */
    public static LogBookCommand<TimingRecord> commitAntennaRegistration(
            LocationId locationId,
            RegistrationId registrationId,
            long effectiveTimeMillis) {

        return commitAutomaticRegistration(
                locationId,
                registrationId,
                effectiveTimeMillis,
                TimingRecord.TagSource.ANT,
                TimingRecord.AutomaticTimeSource.OBS);
    }

    /** Tag/time provenance stays beside the scalar effective time until the TimingRecord is committed. */
    public static LogBookCommand<TimingRecord> commitAutomaticRegistration(
            LocationId locationId,
            RegistrationId registrationId,
            long effectiveTimeMillis,
            TimingRecord.TagSource tagSource,
            TimingRecord.AutomaticTimeSource registrationTimeSource) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(tagSource != null, "tagSource must not be null");
        failIfInvalidArgument(registrationTimeSource != null, "registrationTimeSource must not be null");

        return simple(
                "commitAutomaticRegistration",
                operations -> operations.commitAutomaticRegistration(
                        locationId,
                        registrationId,
                        effectiveTimeMillis,
                        tagSource,
                        registrationTimeSource));
    }

    /** Manual time-source metadata is retained independently from its scalar effective time. */
    public static LogBookCommand<TimingRecord> commitManualRegistration(
            LocationId locationId,
            RegistrationId registrationId,
            long effectiveTimeMillis,
            ManualTimeSource registrationTimeSource) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(registrationTimeSource != null, "registrationTimeSource must not be null");

        return simple(
                "commitManualRegistration",
                operations -> operations.commitManualRegistration(
                        locationId,
                        registrationId,
                        effectiveTimeMillis,
                        registrationTimeSource));
    }

    /** A REV repeats the original automatic registration identity/time without looking up history. */
    public static LogBookCommand<TimingRecord> revokeAutomaticRegistration(
            LocationId originalLocationId,
            RegistrationId registrationId,
            long originalTimeMillis) {

        requireRevokeInput(originalLocationId, registrationId);
        return simple(
                "revokeAutomaticRegistration",
                operations -> operations.revokeAutomaticRegistration(
                        originalLocationId,
                        registrationId,
                        originalTimeMillis));
    }

    /** A manual REV also repeats the original manual time-source classification. */
    public static LogBookCommand<TimingRecord> revokeManualRegistration(
            LocationId originalLocationId,
            RegistrationId registrationId,
            long originalTimeMillis,
            ManualTimeSource originalTimeSource) {

        requireRevokeInput(originalLocationId, registrationId);
        failIfInvalidArgument(originalTimeSource != null, "originalTimeSource must not be null");
        return simple(
                "revokeManualRegistration",
                operations -> operations.revokeManualRegistration(
                        originalLocationId,
                        registrationId,
                        originalTimeMillis,
                        originalTimeSource));
    }

    /** Revoke commands require stable original identity fields; time itself is already a primitive value. */
    private static void requireRevokeInput(LocationId originalLocationId, RegistrationId registrationId) {

        failIfInvalidArgument(originalLocationId != null, "originalLocationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
    }

    /** Command names remain available to execution/logging without introducing one class per command. */
    private static <R> LogBookCommand<R> simple(String name, LogBookCommand.Action<R> action) {
        return new LogBookCommand<R>(name, action);
    }
}
