package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Standard state-changing commands committed through the LogBook component.
 *
 * <p>Factory methods validate caller input and capture the values needed by the
 * immutable command. LogBook later applies that command on the Data Lane.</p>
 */
public final class LogBookCommands {
    /** Prevents construction of this stateless command factory. */
    private LogBookCommands() {
    }

    /** Commits one NODE_OPEN record selected by the owning TimingNode. */
    public static LogBookCommand<Void> commitNodeOpen(LocationId locationId) {

        checkArgument(locationId != null, "locationId must not be null");
        return simple(
                "commitNodeOpen",
                operations -> {
                    operations.commitNodeOpen(locationId);
                    return null;
                });
    }

    /** Commits one NODE_CLOSE record selected by the owning TimingNode. */
    public static LogBookCommand<Void> commitNodeClose(LocationId locationId) {

        checkArgument(locationId != null, "locationId must not be null");
        return simple(
                "commitNodeClose",
                operations -> {
                    operations.commitNodeClose(locationId);
                    return null;
                });
    }

    /** Commits one antenna-originated automatic registration. */
    public static LogBookCommand<RegistrationResult>
            addAutomaticRegistration(
                    LocationId locationId,
                    RegistrationId registrationId,
                    TimingTimestamp effectiveTime) {

        return commitAutomaticRegistration(
                locationId,
                registrationId,
                effectiveTime,
                TimingData.TagSource.ANT,
                TimingData.AutomaticTimeSource.OBS);
    }

    /** Commits one automatic registration with explicit source provenance. */
    public static LogBookCommand<RegistrationResult>
            commitAutomaticRegistration(
                    LocationId locationId,
                    RegistrationId registrationId,
                    TimingTimestamp effectiveTime,
                    TimingData.TagSource tagSource,
                    TimingData.AutomaticTimeSource timeSource) {

        checkArgument(locationId != null, "locationId must not be null");
        checkArgument(registrationId != null, "registrationId must not be null");
        checkArgument(effectiveTime != null, "effectiveTime must not be null");
        checkArgument(tagSource != null, "tagSource must not be null");
        checkArgument(timeSource != null, "timeSource must not be null");

        return simple(
                "commitAutomaticRegistration",
                operations -> operations.commitAutomaticRegistration(
                        locationId,
                        registrationId,
                        effectiveTime,
                        tagSource,
                        timeSource));
    }

    /** Commits one automatic registration using current node time. */
    public static LogBookCommand<RegistrationResult>
            commitAutomaticRegistrationNow(
                    LocationId locationId,
                    RegistrationId registrationId,
                    TimingData.TagSource tagSource) {

        checkArgument(locationId != null, "locationId must not be null");
        checkArgument(registrationId != null, "registrationId must not be null");
        checkArgument(tagSource != null, "tagSource must not be null");

        return simple(
                "commitAutomaticRegistrationNow",
                operations -> operations.commitAutomaticRegistrationNow(
                        locationId,
                        registrationId,
                        tagSource));
    }

    /**
     * Commits one operator/manual registration with its explicit time source.
     */
    public static LogBookCommand<RegistrationResult>
            commitManualRegistration(
                    LocationId locationId,
                    RegistrationId registrationId,
                    TimingTimestamp effectiveTime,
                    ManualTimeSource registrationTimeSource) {

        checkArgument(locationId != null, "locationId must not be null");
        checkArgument(registrationId != null, "registrationId must not be null");
        checkArgument(effectiveTime != null, "effectiveTime must not be null");
        checkArgument(
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
    public static LogBookCommand<RegistrationResult>
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
    public static LogBookCommand<RegistrationResult>
            revokeManualRegistration(
                    LocationId originalLocationId,
                    RegistrationId registrationId,
                    TimingTimestamp originalTime,
                    ManualTimeSource originalTimeSource) {

        requireRevokeInput(
                originalLocationId,
                registrationId,
                originalTime);
        checkArgument(
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

        checkArgument(
                originalLocationId != null,
                "originalLocationId must not be null");
        checkArgument(
                registrationId != null,
                "registrationId must not be null");
        checkArgument(
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
