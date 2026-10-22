package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyUpdate;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.time.Instant;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Presentation-facing application proxy for one TimingNode. */
public final class TimingNodeProxy {

    public enum RegistrationRecordType {
        AUTO_REG,
        MAN_REG
    }

    /** Immutable metadata for the active NodeService registration projection. */
    public static final class RegistrationProjectionInfo {
        private final LocationId locationId;
        private final int count;
        private final int uniqueCount;
        private final long lastMutationSequence;
        private final long lastRevokeSequence;

        private RegistrationProjectionInfo(
                LocationId locationId,
                int count,
                int uniqueCount,
                long lastMutationSequence,
                long lastRevokeSequence) {
            this.locationId = locationId;
            this.count = count;
            this.uniqueCount = uniqueCount;
            this.lastMutationSequence = lastMutationSequence;
            this.lastRevokeSequence = lastRevokeSequence;
        }

        public LocationId locationId() {
            return locationId;
        }

        public int count() {
            return count;
        }

        public int uniqueCount() {
            return uniqueCount;
        }

        public long lastMutationSequence() {
            return lastMutationSequence;
        }

        public long lastRevokeSequence() {
            return lastRevokeSequence;
        }
    }

    /** One chronological registration ADD row from the active projection. */
    public static final class RegistrationEntry {
        private final long sequenceNumber;
        private final RegistrationId registrationId;
        private final TimingTimestamp effectiveTime;
        private final boolean valid;

        private RegistrationEntry(
                long sequenceNumber,
                RegistrationId registrationId,
                TimingTimestamp effectiveTime,
                boolean valid) {
            this.sequenceNumber = sequenceNumber;
            this.registrationId = registrationId;
            this.effectiveTime = effectiveTime;
            this.valid = valid;
        }

        public long sequenceNumber() {
            return sequenceNumber;
        }

        public RegistrationId registrationId() {
            return registrationId;
        }

        public TimingTimestamp effectiveTime() {
            return effectiveTime;
        }

        public boolean valid() {
            return valid;
        }
    }


    private final NodeId timingNodeId;
    private final TimingNode timingNode;
    private final NodeService nodeService;
    private final LogBookAccessor logBook;
    private final LogBookTestControl logBookTestControl;
    private final Event<TimingNodeStatus> statusChangedEvent = new Event<TimingNodeStatus>();

    public final EventSource<TimingNodeStatus> STATUS_CHANGED = statusChangedEvent;
    public final EventSource<TimingRecord> NEW_DATA_ENTRY;

    TimingNodeProxy(
            TimingNode timingNode,
            NodeService nodeService,
            LogBookAccessor logBook) {
        failIfInvalidArgument(timingNode != null, "timingNode must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        this.timingNodeId = timingNode.nodeId();
        this.timingNode = timingNode;
        this.nodeService = nodeService;
        this.logBook = logBook;
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

    public RegistrationId registrationIdFor(TeamId teamId) {
        failIfInvalidArgument(teamId != null, "teamId must not be null");
        return timingNode.registrationIdFor(teamId);
    }

    /** Sets one RegistrationId's engineering stage-start value in normalized UTC milliseconds. */
    public void setStageStart(
            RegistrationId registrationId,
            long startMillis) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        nodeService.setStageStart(registrationId, startMillis);
    }

    /** Returns one stable metadata snapshot of the active registration projection. */
    public RegistrationProjectionInfo registrationProjectionInfo() {

        return registrationProjectionInfo(
                nodeService.registrationProjectionInfo());
    }

    /** Visits one bounded page and returns metadata from the same ordered read turn. */
    public RegistrationProjectionInfo visitRegistrationPage(
            long afterSequence,
            int limit,
            Consumer<RegistrationEntry> visitor) {

        failIfInvalidArgument(afterSequence >= 0L, "afterSequence must not be negative");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        NodeService.RegistrationProjectionInfo info =
                nodeService.visitRegistrationPage(
                        afterSequence,
                        limit,
                        registration -> visitor.accept(
                                registrationEntry(registration)));
        return registrationProjectionInfo(info);
    }

    /** Materializes one compact domain row at the presentation-facing application boundary. */
    private static RegistrationEntry registrationEntry(
            ActiveRegistrations.Registration registration) {

        return new RegistrationEntry(
                registration.sequenceNumber(),
                registration.registrationId(),
                new TimingTimestamp(
                        Instant.ofEpochMilli(
                                registration.effectiveTimeMillis())),
                registration.isValid());
    }

    private static RegistrationProjectionInfo registrationProjectionInfo(
            NodeService.RegistrationProjectionInfo info) {

        return new RegistrationProjectionInfo(
                info.locationId(),
                info.count(),
                info.uniqueCount(),
                info.lastMutationSequence(),
                info.lastRevokeSequence());
    }

    /** Visits chronological registration rows after one original ADD sequence. */
    public int visitRegistrationsAfter(
            long afterSequence,
            int limit,
            Consumer<RegistrationEntry> visitor) {

        failIfInvalidArgument(afterSequence >= 0L, "afterSequence must not be negative");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        return nodeService.visitRegistrationsAfter(
                afterSequence,
                limit,
                registration -> visitor.accept(
                        registrationEntry(registration)));
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
                            effectiveTime.instant().toEpochMilli(),
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
                            effectiveTime.instant().toEpochMilli(),
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
                        originalTime.instant().toEpochMilli());
                break;
            case MAN_REG:
                failIfInvalidArgument(
                        originalTimeSource != null,
                        "MAN_REG revoke requires originalTimeSource");
                command = LogBookCommands.revokeManualRegistration(
                        originalLocationId,
                        registrationId,
                        originalTime.instant().toEpochMilli(),
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
