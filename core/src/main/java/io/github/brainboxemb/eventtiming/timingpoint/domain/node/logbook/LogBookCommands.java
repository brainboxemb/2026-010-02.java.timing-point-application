package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;

/**
 * Standard state-changing commands committed through the LogBook component.
 *
 * <p>The factory validates command input at creation time. Execution belongs to
 * {@link LogBook}, which applies the command on the Data Lane.</p>
 */
public final class LogBookCommands {
    private static final LogBookCommand<CloseResult> CLOSE =
            simple("close", LogBookState::close);

    private LogBookCommands() {
    }

    public static LogBookCommand<OpenResult> open(LocationId locationId) {
        if (locationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
        return simple(
                "open",
                logic -> logic.open(locationId));
    }

    public static LogBookCommand<CloseResult> close() {
        return CLOSE;
    }

    public static LogBookCommand<RegistrationResult>
            addAutomaticRegistration(
                    RegistrationId registrationId,
                    TimingTimestamp time) {
        if (registrationId == null) {
            throw new IllegalArgumentException("registrationId must not be null");
        }
        if (time == null) {
            throw new IllegalArgumentException("time must not be null");
        }

        return simple(
                "addAutomaticRegistration",
                logic -> logic.addAutomaticRegistration(
                        registrationId,
                        time));
    }

    /** Direct IF-03 simulation with a caller-supplied effective time. */
    public static LogBookCommand<RegistrationResult>
            simulateAutomaticRegistration(
                    RegistrationId registrationId,
                    TimingTimestamp time) {
        if (registrationId == null || time == null) {
            throw new IllegalArgumentException("registrationId and time are required");
        }
        return simple(
                "simulateAutomaticRegistration",
                logic -> logic.simulateAutomaticRegistration(registrationId, time));
    }

    /** Direct IF-03 simulation; capture TimeSource on the node's serial lane. */
    public static LogBookCommand<RegistrationResult>
            simulateAutomaticRegistrationNow(RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException("registrationId must not be null");
        }
        return simple(
                "simulateAutomaticRegistrationNow",
                logic -> logic.simulateAutomaticRegistrationNow(registrationId));
    }

    public static LogBookCommand<RegistrationResult>
            commitManualRegistration(
                    RegistrationId registrationId,
                    TimingTimestamp effectiveTime,
                    ManualTimeSource registrationTimeSource) {
        if (registrationId == null) {
            throw new IllegalArgumentException("registrationId must not be null");
        }
        if (effectiveTime == null) {
            throw new IllegalArgumentException("effectiveTime must not be null");
        }
        if (registrationTimeSource == null) {
            throw new IllegalArgumentException(
                    "registrationTimeSource must not be null");
        }

        return simple(
                "commitManualRegistration",
                logic -> logic.commitManualRegistration(
                        registrationId,
                        effectiveTime,
                        registrationTimeSource));
    }

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
                logic -> logic.revokeAutomaticRegistration(
                        originalLocationId,
                        registrationId,
                        originalTime));
    }

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
        if (originalTimeSource == null) {
            throw new IllegalArgumentException(
                    "originalTimeSource must not be null");
        }
        return simple(
                "revokeManualRegistration",
                logic -> logic.revokeManualRegistration(
                        originalLocationId,
                        registrationId,
                        originalTime,
                        originalTimeSource));
    }

    private static void requireRevokeInput(
            LocationId originalLocationId,
            RegistrationId registrationId,
            TimingTimestamp originalTime) {
        if (originalLocationId == null) {
            throw new IllegalArgumentException(
                    "originalLocationId must not be null");
        }
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "registrationId must not be null");
        }
        if (originalTime == null) {
            throw new IllegalArgumentException(
                    "originalTime must not be null");
        }
    }

    private static <R> LogBookCommand<R> simple(
            String name,
            LogBookCommand.Action<R> action) {
        return new LogBookCommand<>(
                name,
                action);
    }

}
