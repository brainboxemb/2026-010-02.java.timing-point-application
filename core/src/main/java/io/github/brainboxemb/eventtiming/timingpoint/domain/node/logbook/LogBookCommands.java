package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Standard TimingData commit commands executed through the LogBook component.
 *
 * <p>Factory methods validate caller input and capture the values needed by the
 * command. LogBook later applies that command on the Data Lane.</p>
 */
public final class LogBookCommands {
    /** Prevents construction of this stateless command factory. */
    private LogBookCommands() {
    }

    /** Commits one NODE_OPEN TimingData record selected by the owning TimingNode. */
    public static LogBookCommand<Void> commitNodeOpen(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        return simple(
                "commitNodeOpen",
                operations -> {
                    operations.commitNodeOpen(locationId);
                    return null;
                });
    }

    /** Commits one NODE_CLOSE TimingData record selected by the owning TimingNode. */
    public static LogBookCommand<Void> commitNodeClose(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        return simple(
                "commitNodeClose",
                operations -> {
                    operations.commitNodeClose(locationId);
                    return null;
                });
    }

    /** Commits one automatic registration selected from an antenna observation. */
    public static LogBookCommand<TimingRecord>
            commitAntennaRegistration(
                    LocationId locationId,
                    RegistrationId registrationId,
                    TimingTimestamp effectiveTime) {

        return commitAutomaticRegistration(
                locationId,
                registrationId,
                effectiveTime,
                TimingRecord.TagSource.ANT,
                TimingRecord.AutomaticTimeSource.OBS);
    }

    /** Commits one automatic registration with explicit tag and time sources. */
    public static LogBookCommand<TimingRecord>
            commitAutomaticRegistration(
                    LocationId locationId,
                    RegistrationId registrationId,
                    TimingTimestamp effectiveTime,
                    TimingRecord.TagSource tagSource,
                    TimingRecord.AutomaticTimeSource registrationTimeSource) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(effectiveTime != null, "effectiveTime must not be null");
        failIfInvalidArgument(tagSource != null, "tagSource must not be null");
        failIfInvalidArgument(registrationTimeSource != null, "registrationTimeSource must not be null");

        return simple(
                "commitAutomaticRegistration",
                operations -> operations.commitAutomaticRegistration(
                        locationId,
                        registrationId,
                        effectiveTime,
                        tagSource,
                        registrationTimeSource));
    }

    /**
     * Commits one operator/manual registration with its explicit time source.
     */
    public static LogBookCommand<TimingRecord>
            commitManualRegistration(
                    LocationId locationId,
                    RegistrationId registrationId,
                    TimingTimestamp effectiveTime,
                    ManualTimeSource registrationTimeSource) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(effectiveTime != null, "effectiveTime must not be null");
        failIfInvalidArgument(
                registrationTimeSource != null,
                "registrationTimeSource must not be null");

        return simple(
                "commitManualRegistration",
                operations -> operations.commitManualRegistration(
                        locationId,
                        registrationId,
                        effectiveTime,
                        registrationTimeSource));
    }

    /**
     * Appends a REV for the identified previously committed automatic registration.
     */
    public static LogBookCommand<TimingRecord>
            revokeAutomaticRegistration(
                    LocationId originalLocationId,
                    RegistrationId registrationId,
                    TimingTimestamp originalTime) {

        requireRevokeInput(
                originalLocationId,
                registrationId,
                originalTime);
        return simple(
                "revokeAutomaticRegistration",
                operations -> operations.revokeAutomaticRegistration(
                        originalLocationId,
                        registrationId,
                        originalTime));
    }

    /**
     * Appends a REV for the identified previously committed manual registration.
     */
    public static LogBookCommand<TimingRecord>
            revokeManualRegistration(
                    LocationId originalLocationId,
                    RegistrationId registrationId,
                    TimingTimestamp originalTime,
                    ManualTimeSource originalTimeSource) {

        requireRevokeInput(
                originalLocationId,
                registrationId,
                originalTime);
        failIfInvalidArgument(
                originalTimeSource != null,
                "originalTimeSource must not be null");
        return simple(
                "revokeManualRegistration",
                operations -> operations.revokeManualRegistration(
                        originalLocationId,
                        registrationId,
                        originalTime,
                        originalTimeSource));
    }

    /** Validates the shared identity/time fields required by registration revocation commands. */
    private static void requireRevokeInput(
            LocationId originalLocationId,
            RegistrationId registrationId,
            TimingTimestamp originalTime) {

        failIfInvalidArgument(
                originalLocationId != null,
                "originalLocationId must not be null");
        failIfInvalidArgument(
                registrationId != null,
                "registrationId must not be null");
        failIfInvalidArgument(
                originalTime != null,
                "originalTime must not be null");
    }

    /** Creates one named typed command from its deferred LogBookOperations action. */
    private static <R> LogBookCommand<R> simple(
            String name,
            LogBookCommand.Action<R> action) {

        return new LogBookCommand<>(
                name,
                action);
    }

}
