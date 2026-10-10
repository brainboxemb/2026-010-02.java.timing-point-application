package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;

/**
 * Standard state-changing commands supported by TimingNode.
 *
 * <p>The factory validates command input at creation time. Execution remains the
 * responsibility of {@link TimingNode}, which applies the command to its
 * package-private logic on the serial lane.</p>
 */
public final class TimingNodeCommands {
    private static final TimingNodeCommand<CloseResult> CLOSE =
            simple("close", TimingNodeLogic::close);

    private TimingNodeCommands() {
    }

    public static TimingNodeCommand<OpenResult> open(LocationId locationId) {
        if (locationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
        return simple(
                "open",
                logic -> logic.open(locationId));
    }

    public static TimingNodeCommand<CloseResult> close() {
        return CLOSE;
    }

    public static TimingNodeCommand<RegistrationResult>
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
    public static TimingNodeCommand<RegistrationResult>
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
    public static TimingNodeCommand<RegistrationResult>
            simulateAutomaticRegistrationNow(RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException("registrationId must not be null");
        }
        return simple(
                "simulateAutomaticRegistrationNow",
                logic -> logic.simulateAutomaticRegistrationNow(registrationId));
    }

    public static TimingNodeCommand<RegistrationResult>
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

    public static TimingNodeCommand<RegistrationResult>
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

    public static TimingNodeCommand<RegistrationResult>
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

    private static <R> TimingNodeCommand<R> simple(
            String name,
            TimingNodeCommand.Action<R> action) {
        return new TimingNodeCommand<>(
                name,
                action,
                (node, result) -> result);
    }

}
