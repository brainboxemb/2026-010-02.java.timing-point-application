package oss.relay.timing.timingpoint.application;

import oss.relay.timing.eventdata.EventData;
import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.LocationId;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingRecord.ManualTimeSource;
import oss.relay.timing.timingdata.TimingTimestamp;
import oss.relay.timing.timingpoint.domain.node.TimingNode;
import oss.relay.timing.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import oss.relay.timing.timingpoint.domain.node.TimingNodeTypes.FunctionalState;
import oss.relay.timing.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookAccessor;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookCommand;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookCommands;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookQueries;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookBackup;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookTestControl;

import java.nio.file.Path;
import oss.relay.timing.timingpoint.domain.node.registration.ActiveRegistrations;
import oss.relay.timing.timingpoint.domain.node.registration.StageTiming;
import oss.relay.timing.timingpoint.domain.node.service.state.NodeStateAccessor;
import oss.relay.timing.timingpoint.domain.node.service.state.NodeStateTestControl;
import oss.relay.timing.timingpoint.infra.property.PropertyUpdate;
import oss.relay.timing.timingpoint.platform.events.Event;
import oss.relay.timing.timingpoint.platform.events.EventSource;
import oss.relay.timing.timingpoint.platform.time.TimeSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfIllegalState;
import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

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

        /** Stage-start knowledge materialized from the retained compact UTC value. */
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

    /** Metadata for current applied StageStartTimes. */
    public static final class StageStartProjectionInfo {
        private final int count;
        private final long revision;

        private StageStartProjectionInfo(
                int count,
                long revision) {
            this.count = count;
            this.revision = revision;
        }

        public int count() {
            return count;
        }

        public long revision() {
            return revision;
        }
    }

    /** One current applied StageStartTime row. */
    public static final class StageStartEntry {
        private final RegistrationId registrationId;
        private final TimingTimestamp start;

        private StageStartEntry(
                RegistrationId registrationId,
                TimingTimestamp start) {
            this.registrationId = registrationId;
            this.start = start;
        }

        public RegistrationId registrationId() {
            return registrationId;
        }

        public TimingTimestamp start() {
            return start;
        }
    }

    /** Immutable metadata for the active NodeState registration projection. */
    public static final class RegistrationProjectionInfo {
        private final LocationId locationId;
        private final int count;
        private final int uniqueCount;
        private final long lastMutationSequence;
        private final long lastRevokeSequence;
        private final long stageStartRevision;

        private RegistrationProjectionInfo(
                LocationId locationId,
                int count,
                int uniqueCount,
                long lastMutationSequence,
                long lastRevokeSequence,
                long stageStartRevision) {
            this.locationId = locationId;
            this.count = count;
            this.uniqueCount = uniqueCount;
            this.lastMutationSequence = lastMutationSequence;
            this.lastRevokeSequence = lastRevokeSequence;
            this.stageStartRevision = stageStartRevision;
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

        public long stageStartRevision() {
            return stageStartRevision;
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

        /** Returns the maintained stage-start knowledge, or null while RaceData has none. */
        public TimingTimestamp stageStart() {
            return stageStart;
        }

        /** Returns the maintained runtime, or null while the stage start is not resolvable. */
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
    private final EventData eventData;
    private final NodeStateAccessor nodeState;
    private final NodeStateTestControl nodeStateTestControl;
    private final LogBookAccessor logBook;
    private final LogBookTestControl logBookTestControl;
    private final LogBookBackup logBookBackup;
    private final Event<TimingNodeStatus> statusChangedEvent = new Event<TimingNodeStatus>();

    public final EventSource<TimingNodeStatus> STATUS_CHANGED = statusChangedEvent;
    public final EventSource<TimingRecord> NEW_DATA_ENTRY;

    TimingNodeProxy(
            TimingNode timingNode,
            EventData eventData,
            NodeStateAccessor nodeState,
            NodeStateTestControl nodeStateTestControl,
            LogBookAccessor logBook,
            LogBookTestControl logBookTestControl,
            LogBookBackup logBookBackup) {
        failIfInvalidArgument(timingNode != null, "timingNode must not be null");
        failIfInvalidArgument(eventData != null, "eventData must not be null");
        failIfInvalidArgument(nodeState != null, "nodeState must not be null");
        failIfInvalidArgument(nodeStateTestControl != null, "nodeStateTestControl must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(
                logBookTestControl != null,
                "logBookTestControl must not be null");
        failIfInvalidArgument(
                logBookBackup != null,
                "logBookBackup must not be null");
        this.timingNodeId = timingNode.nodeId();
        this.timingNode = timingNode;
        this.eventData = eventData;
        this.nodeState = nodeState;
        this.nodeStateTestControl = nodeStateTestControl;
        this.logBook = logBook;
        this.logBookTestControl = logBookTestControl;
        this.logBookBackup = logBookBackup;
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
        LocationId locationId = timingNode.locationId();
        failIfIllegalState(locationId != null, "TimingNode is not open");
        return eventData.registrationIdFor(locationId, teamId);
    }

    /** Applies one engineering-only stage-start override. */
    public void overrideStageStart(
            RegistrationId registrationId,
            long startMillis) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        nodeStateTestControl.overrideStageStart(registrationId, startMillis);
    }

    /**
     * Returns one coherent technical snapshot of current live RaceData.
     *
     * <p>Mappings contain only live Central assignments. Fixed EventData mappings are excluded.
     * Stage starts are materialized from retained compact UTC values only here.</p>
     */
    public RaceDataSnapshot raceDataSnapshot() {

        List<RaceDataMapping> mappings = new ArrayList<RaceDataMapping>();
        List<RaceDataStageStart> stageStarts = new ArrayList<RaceDataStageStart>();
        nodeState.visitRaceData(
                (registrationId, teamId) ->
                        mappings.add(new RaceDataMapping(registrationId, teamId)),
                (registrationId, stageStart) ->
                        stageStarts.add(
                                new RaceDataStageStart(
                                        registrationId,
                                        StageTiming.timestamp(
                                                stageStart.longValue()))));

        Comparator<RaceDataMapping> mappingOrder =
                Comparator.comparing(value -> value.registrationId().value());
        Comparator<RaceDataStageStart> startOrder =
                Comparator.comparing(value -> value.registrationId().value());
        Collections.sort(mappings, mappingOrder);
        Collections.sort(stageStarts, startOrder);
        return new RaceDataSnapshot(mappings, stageStarts);
    }

    /** Returns one stable snapshot of applied StageStartTimes in RegistrationId order. */
    public StageStartProjectionInfo visitStageStarts(
            int offset,
            int limit,
            Consumer<StageStartEntry> visitor) {

        failIfInvalidArgument(offset >= 0, "offset must not be negative");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        List<StageStartEntry> rows = new ArrayList<StageStartEntry>();
        NodeStateAccessor.StageStartProjectionInfo info =
                nodeState.visitStageStarts(
                        (registrationId, stageStart) ->
                                rows.add(
                                        new StageStartEntry(
                                                registrationId,
                                                StageTiming.timestamp(
                                                        stageStart.longValue()))));
        Collections.sort(
                rows,
                Comparator.comparing(value -> value.registrationId().value()));

        int from = Math.min(offset, rows.size());
        int to = Math.min(from + limit, rows.size());
        for (int index = from; index < to; index++) {
            visitor.accept(rows.get(index));
        }
        return new StageStartProjectionInfo(
                info.count(),
                info.revision());
    }

    /** Returns current applied StageStartTimes metadata without emitting rows. */
    public StageStartProjectionInfo stageStartProjectionInfo() {

        NodeStateAccessor.StageStartProjectionInfo info =
                nodeState.visitStageStarts(
                        (registrationId, stageStart) -> { });
        return new StageStartProjectionInfo(
                info.count(),
                info.revision());
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
                        ? StageTiming.timestamp(
                                registration.stageStart())
                        : null;
        Long elapsedMillis =
                registration.hasElapsedTime()
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
                info.lastRevokeSequence(),
                info.stageStartRevision());
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

    public Path backupLogBook() {
        return logBookBackup.backupLogBook();
    }

    public Path requestLogBookBackupAndClearOnRestart() {
        return logBookBackup.requestBackupAndClearOnRestart();
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
