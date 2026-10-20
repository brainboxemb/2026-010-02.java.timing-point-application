package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.FunctionalState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommand;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookTestControl;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyUpdate;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Presentation-facing application proxy for one TimingNode. */
public final class TimingNodeProxy {

    public enum RegistrationRecordType {
        AUTO_REG,
        MAN_REG
    }

    private final NodeId timingNodeId;
    private final TimingNode timingNode;
    private final LogBookAccessor logBook;
    private final LogBookTestControl logBookTestControl;
    private final Event<TimingNodeStatus> statusChangedEvent = new Event<TimingNodeStatus>();

    public final EventSource<TimingNodeStatus> STATUS_CHANGED = statusChangedEvent;
    public final EventSource<TimingRecord> NEW_DATA_ENTRY;

    TimingNodeProxy(TimingNode timingNode) {
        failIfInvalidArgument(timingNode != null, "timingNode must not be null");
        this.timingNodeId = timingNode.nodeId();
        this.timingNode = timingNode;
        this.logBook = timingNode.logBook();
        if (!(logBook instanceof LogBookTestControl)) {
            throw new IllegalStateException(
                    "TimingNode LogBook does not expose engineering test control");
        }
        this.logBookTestControl = (LogBookTestControl) logBook;
        this.NEW_DATA_ENTRY = timingNode.NEW_DATA_ENTRY;
        timingNode.PROPERTIES.CHANGED.subscribe(this::publishVisibleStatusChange);
    }

    public NodeId timingNodeId() {
        return timingNodeId;
    }

    public TimeSource timeSource() {
        return timingNode.timeSource();
    }

    public TimingNodeStatus status() {
        return TimingNodeStatus.current(
                timingNodeId,
                timingNode.functionalState(),
                timingNode.locationId());
    }

    public OpenResult open(LocationId locationId) {
        return timingNode.open(locationId);
    }

    public CloseResult close() {
        return timingNode.close();
    }

    public void testNextLogBookWriteFailure(LogBookTestControl.WriteFailure failure) {
        logBookTestControl.armNextWriteFailure(failure);
    }

    public CommandResult addTestAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingRecord.AutomaticTimeSource registrationTimeSource) {

        failIfInvalidArgument(
                registrationTimeSource == TimingRecord.AutomaticTimeSource.API
                        || registrationTimeSource == TimingRecord.AutomaticTimeSource.NODE,
                "test automatic registration time source must be API or NODE");

        Supplier<CommandResult> addRegistration = () -> {
            if (timingNode.functionalState() != FunctionalState.OPEN) {
                return CommandResult.nodeNotOpen();
            }

            LogBookCommand<TimingRecord> command =
                    LogBookCommands.commitAutomaticRegistration(
                            timingNode.locationId(),
                            registrationId,
                            effectiveTime,
                            TimingRecord.TagSource.API,
                            registrationTimeSource);
            return CommandResult.committed(logBook.process(command));
        };

        return timingNode.processLocked(addRegistration);
    }

    public CommandResult addManualRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource timeSource) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(effectiveTime != null, "effectiveTime must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");

        Supplier<CommandResult> addRegistration = () -> {
            if (timingNode.functionalState() != FunctionalState.OPEN) {
                return CommandResult.nodeNotOpen();
            }

            LogBookCommand<TimingRecord> command =
                    LogBookCommands.commitManualRegistration(
                            timingNode.locationId(),
                            registrationId,
                            effectiveTime,
                            timeSource);
            return CommandResult.committed(logBook.process(command));
        };

        return timingNode.processLocked(addRegistration);
    }

    public CommandResult revokeRegistration(
            RegistrationRecordType recordType,
            LocationId originalLocationId,
            RegistrationId registrationId,
            TimingTimestamp originalTime,
            ManualTimeSource originalTimeSource) {

        failIfInvalidArgument(recordType != null, "recordType must not be null");
        failIfInvalidArgument(originalLocationId != null, "originalLocationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(originalTime != null, "originalTime must not be null");

        LogBookCommand<TimingRecord> command;
        switch (recordType) {
            case AUTO_REG:
                failIfInvalidArgument(
                        originalTimeSource == null,
                        "AUTO_REG revoke must not carry originalTimeSource");
                command = LogBookCommands.revokeAutomaticRegistration(
                        originalLocationId,
                        registrationId,
                        originalTime);
                break;
            case MAN_REG:
                failIfInvalidArgument(
                        originalTimeSource != null,
                        "MAN_REG revoke requires originalTimeSource");
                command = LogBookCommands.revokeManualRegistration(
                        originalLocationId,
                        registrationId,
                        originalTime,
                        originalTimeSource);
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported registration record type " + recordType);
        }

        return timingNode.processLocked(
                () -> CommandResult.committed(logBook.process(command)));
    }

    public int logBookCount() {
        return logBook.process(LogBookQueries.timingDataCount());
    }

    public int visitLogBookFrom(
            long fromSequence,
            int limit,
            Consumer<TimingRecord> visitor) {
        return logBook.process(
                LogBookQueries.visitTimingRecordRange(
                        fromSequence,
                        limit,
                        visitor));
    }

    public int visitLatestLogBook(
            int limit,
            Consumer<TimingRecord> visitor) {
        return logBook.process(
                LogBookQueries.visitLatestTimingRecords(limit, visitor));
    }

    private void publishVisibleStatusChange(PropertyUpdate update) {
        if (update.contains(TimingNode.FUNCTIONAL_STATE)
                || update.contains(TimingNode.LOCATION_ID)) {
            statusChangedEvent.emit(status());
        }
    }
}
