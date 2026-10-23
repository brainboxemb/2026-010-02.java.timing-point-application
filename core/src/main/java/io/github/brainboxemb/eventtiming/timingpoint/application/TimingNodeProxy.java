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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeStateAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyUpdate;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Presentation-facing application proxy for one TimingNode. */
public final class TimingNodeProxy {

    public enum RegistrationRecordType {
        AUTO_REG,
        MAN_REG
    }

    /** One live Central-owned RegistrationId-to-TeamId assignment. */
    public static final class RaceDataMapping {
        private final RegistrationId registrationId;
        private final TeamId teamId;

        private RaceDataMapping(RegistrationId registrationId, TeamId teamId) {
            this.registrationId = registrationId;
            this.teamId = teamId;
        }

        /** Registration identity carrying this live Central assignment. */
        public RegistrationId registrationId() {
            return registrationId;
        }

        /** Live TeamId assigned by Central. */
        public TeamId teamId() {
            return teamId;
        }
    }

    /** One current RegistrationId-scoped stage start materialized at the application boundary. */
    public static final class RaceDataStageStart {
        private final RegistrationId registrationId;
        private final TimingTimestamp start;

        private RaceDataStageStart(RegistrationId registrationId, TimingTimestamp start) {
            this.registrationId = registrationId;
            this.start = start;
        }

        /** Registration identity whose stage start is retained. */
        public RegistrationId registrationId() {
            return registrationId;
        }

        /** Absolute UTC stage-start timestamp materialized from retained epoch milliseconds. */
        public TimingTimestamp start() {
            return start;
        }
    }

    /** One coherent current RaceData snapshot for technical Presentation inspection. */
    public static final class RaceDataSnapshot {
        private final List<RaceDataMapping> mappings;
        private final List<RaceDataStageStart> stageStarts;

        private RaceDataSnapshot(
                List<RaceDataMapping> mappings,
                List<RaceDataStageStart> stageStarts) {
            this.mappings = Collections.unmodifiableList(mappings);
            this.stageStarts = Collections.unmodifiableList(stageStarts);
        }

        /** Sorted immutable live Central assignment rows. */
        public List<RaceDataMapping> mappings() {
            return mappings;
        }

        /** Sorted immutable current stage-start rows. */
        public List<RaceDataStageStart> stageStarts() {
            return stageStarts;
        }
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
        private final TimingTimestamp stageStart;
        private final Long elapsedMillis;
        private final Integer rank;
        private final boolean valid;

        private RegistrationEntry(
                long sequenceNumber,
                RegistrationId registrationId,
                TimingTimestamp effectiveTime,
                TimingTimestamp stageStart,
                Long elapsedMillis,
                Integer rank,
                boolean valid) {
            this.sequenceNumber = sequenceNumber;
            this.registrationId = registrationId;
            this.effectiveTime = effectiveTime;
            this.stageStart = stageStart;
            this.elapsedMillis = elapsedMillis;
            this.rank = rank;
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

        /** Returns the maintained stage start, or null while RaceData has none. */
        public TimingTimestamp stageStart() {
            return stageStart;
        }

        /** Returns the maintained passage runtime in milliseconds, or null without a start. */
        public Long elapsedMillis() {
            return elapsedMillis;
        }

        /** Returns the maintained competition rank, or null when the row is not rankable. */
        public Integer rank() {
            return rank;
        }

        public boolean valid() {
            return valid;
        }
    }


    private final NodeId timingNodeId;
    private final TimingNode timingNode;
    private final NodeService nodeService;
    private final NodeStateAccessor nodeState;
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
        this.nodeState = nodeService.state();
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

    /**
     * Returns one coherent technical snapshot of current live RaceData.
     *
     * <p>Mappings contain only live Central assignments. Fixed EventData mappings are excluded.
     * Stage starts are materialized from retained epoch-millisecond scalars only here.</p>
     */
    public RaceDataSnapshot raceDataSnapshot() {

        List<RaceDataMapping> mappings = new ArrayList<RaceDataMapping>();
        List<RaceDataStageStart> stageStarts = new ArrayList<RaceDataStageStart>();
        nodeState.visitRaceData(
                (registrationId, teamId) ->
                        mappings.add(new RaceDataMapping(registrationId, teamId)),
                (registrationId, startMillis) ->
                        stageStarts.add(
                                new RaceDataStageStart(
                                        registrationId,
                                        new TimingTimestamp(
                                                Instant.ofEpochMilli(
                                                        startMillis.longValue())))));

        Comparator<RaceDataMapping> mappingOrder =
                Comparator.comparing(value -> value.registrationId().value());
        Comparator<RaceDataStageStart> startOrder =
                Comparator.comparing(value -> value.registrationId().value());
        Collections.sort(mappings, mappingOrder);
        Collections.sort(stageStarts, startOrder);
        return new RaceDataSnapshot(mappings, stageStarts);
    }

    /** Returns one stable metadata snapshot of the active registration projection. */
    public RegistrationProjectionInfo registrationProjectionInfo() {

        return registrationProjectionInfo(
                nodeState.registrationProjectionInfo());
    }

    /** Visits one bounded page and returns metadata from the same ordered read turn. */
    public RegistrationProjectionInfo visitRegistrationPage(
            long afterSequence,
            int limit,
            Consumer<RegistrationEntry> visitor) {

        failIfInvalidArgument(afterSequence >= 0L, "afterSequence must not be negative");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        NodeStateAccessor.RegistrationProjectionInfo info =
                nodeState.visitRegistrationPage(
                        afterSequence,
                        limit,
                        registration -> visitor.accept(
                                registrationEntry(registration)));
        return registrationProjectionInfo(info);
    }

    /** Materializes one compact domain row at the presentation-facing application boundary. */
    private static RegistrationEntry registrationEntry(
            ActiveRegistrations.Registration registration) {

        TimingTimestamp stageStart =
                registration.hasStageStart()
                        ? new TimingTimestamp(
                                Instant.ofEpochMilli(
                                        registration.stageStartMillis()))
                        : null;
        Long elapsedMillis =
                registration.hasStageStart()
                        ? Long.valueOf(registration.elapsedMillis())
                        : null;
        Integer rank =
                registration.rank() > 0
                        ? Integer.valueOf(registration.rank())
                        : null;

        return new RegistrationEntry(
                registration.sequenceNumber(),
                registration.registrationId(),
                new TimingTimestamp(
                        Instant.ofEpochMilli(
                                registration.effectiveTimeMillis())),
                stageStart,
                elapsedMillis,
                rank,
                registration.isValid());
    }

    private static RegistrationProjectionInfo registrationProjectionInfo(
            NodeStateAccessor.RegistrationProjectionInfo info) {

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

        return nodeState.visitRegistrationsAfter(
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
