package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Standard state-changing commands committed through the LogBook component.
 *
 * <p>The factory validates command input at creation time. Execution belongs to
 * {@link LogBook}, which applies the command on the Data Lane.</p>
 *
 * <p>Factory methods validate caller input and capture only the values needed
 * by the immutable command. LogBook later applies that command through its owned
 * LogBookOperations on the Data Lane.</p>
 */
public final class LogBookCommands {
    private static final LogBookCommand<CloseResult> CLOSE =
            simple("close", LogBookOperations::close);

    /** Prevents construction of this stateless command factory. */
    private LogBookCommands() {
    }

    /**
     * Opens the node for the supplied timing location.
     *
     * <p>The command is idempotent; executing it while already open returns
     * {@code ALREADY_OPEN} rather than creating a second open transition.</p>
     */
    public static LogBookCommand<OpenResult> open(LocationId locationId) {

        checkArgument(locationId != null, "locationId must not be null");
        return simple(
                "open",
                operations -> operations.open(locationId));
    }

    /**
     * Closes the currently open timing location.
     *
     * <p>The command is idempotent when the node is already closed.</p>
     */
    public static LogBookCommand<CloseResult> close() {

        return CLOSE;
    }

    /** Commits one antenna-originated automatic registration. */
    public static LogBookCommand<RegistrationResult>
            addAutomaticRegistration(
                    RegistrationId registrationId,
                    TimingTimestamp effectiveTime) {

        return commitAutomaticRegistration(
                registrationId,
                effectiveTime,
                TimingData.TagSource.ANT,
                TimingData.AutomaticTimeSource.OBS);
    }

    /** Commits one automatic registration with explicit source provenance. */
    public static LogBookCommand<RegistrationResult>
            commitAutomaticRegistration(
                    RegistrationId registrationId,
                    TimingTimestamp effectiveTime,
                    TimingData.TagSource tagSource,
                    TimingData.AutomaticTimeSource timeSource) {

        checkArgument(registrationId != null, "registrationId must not be null");
        checkArgument(effectiveTime != null, "effectiveTime must not be null");
        checkArgument(tagSource != null, "tagSource must not be null");
        checkArgument(timeSource != null, "timeSource must not be null");

        return simple(
                "commitAutomaticRegistration",
                operations -> operations.commitAutomaticRegistration(
                        registrationId,
                        effectiveTime,
                        tagSource,
                        timeSource));
    }

    /** Commits one automatic registration using current node time. */
    public static LogBookCommand<RegistrationResult>
            commitAutomaticRegistrationNow(
                    RegistrationId registrationId,
                    TimingData.TagSource tagSource) {

        checkArgument(registrationId != null, "registrationId must not be null");
        checkArgument(tagSource != null, "tagSource must not be null");

        return simple(
                "commitAutomaticRegistrationNow",
                operations -> operations.commitAutomaticRegistrationNow(
                        registrationId,
                        tagSource));
    }

    /**
     * Commits one operator/manual registration with its explicit time source.
     */
    public static LogBookCommand<RegistrationResult>
            commitManualRegistration(
                    RegistrationId registrationId,
                    TimingTimestamp effectiveTime,
                    ManualTimeSource registrationTimeSource) {

        checkArgument(registrationId != null, "registrationId must not be null");
        checkArgument(effectiveTime != null, "effectiveTime must not be null");
        checkArgument(
                registrationTimeSource != null,
                "registrationTimeSource must not be null");

        return simple(
                "commitManualRegistration",
                operations -> operations.commitManualRegistration(
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
