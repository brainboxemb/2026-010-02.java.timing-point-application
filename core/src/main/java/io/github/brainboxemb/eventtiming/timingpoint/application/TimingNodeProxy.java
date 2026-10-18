package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommand;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookTestControl;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.FunctionalState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyUpdate;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Presentation-facing application proxy for one TimingNode.
 *
 * <p>The proxy gives presentation adapters explicit node context while keeping the
 * Domain component behind the Application boundary. It maps presentation intent to
 * typed TimingNode commands/queries and exposes node-scoped application events.</p>
 */
public final class TimingNodeProxy {

    /** Registration record family supplied by a higher interpreting layer. */
    public enum RegistrationRecordType {
        AUTO_REG,
        MAN_REG
    }

    private final NodeId timingNodeId;
    private final TimingNode timingNode;
    private final LogBookAccessor logBook;
    private final LogBookTestControl logBookTestControl;
    private final Event<TimingNodeStatus> statusChangedEvent = new Event<>();

    /** Client-visible TimingNode status changes. */
    public final EventSource<TimingNodeStatus> STATUS_CHANGED = statusChangedEvent;

    /** Presentation-facing post-commit TimingData source. */
    public final EventSource<TimingData> NEW_DATA_ENTRY;

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
        this.NEW_DATA_ENTRY = logBook.newDataEntryEvent();
        timingNode.PROPERTIES.CHANGED.subscribe(this::publishVisibleStatusChange);
    }

    /** Returns this node's ID. */
    public NodeId timingNodeId() {
        return timingNodeId;
    }

    /** Returns the TimeSource configured for this node. This lookup does not block. */
    public TimeSource timeSource() {
        return timingNode.timeSource();
    }

    /** Returns the current client-visible status for this TimingNode. */
    public TimingNodeStatus status() {

        return TimingNodeStatus.current(
                timingNodeId,
                timingNode.functionalState(),
                timingNode.locationId());
    }

    /**
     * Opens the node at the requested LocationId.
     */
    public OpenResult open(LocationId locationId) {
        return timingNode.open(locationId);
    }

    /** Closes the node without waiting for lifecycle-history persistence. */
    public CloseResult close() {
        return timingNode.close();
    }

    /** Arms one engineering/test failure for the next LogBook write. */
    public void testNextLogBookWriteFailure(
            LogBookTestControl.WriteFailure failure) {

        logBookTestControl.armNextWriteFailure(failure);
    }

    /**
     * Adds one test automatic registration using the supplied effective time.
     *
     * <p>This call uses the TimingNode operation lock so OPEN state and LocationId
     * stay unchanged while the result-bearing LogBook command is processed. The caller
     * also identifies whether the supplied time came from this node's TimeSource or was
     * explicitly supplied by an API client.</p>
     */
    public CommandResult addTestAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingData.AutomaticTimeSource registrationTimeSource) {

        failIfInvalidArgument(
                registrationTimeSource == TimingData.AutomaticTimeSource.API
                        || registrationTimeSource == TimingData.AutomaticTimeSource.NODE,
                "test automatic registration time source must be API or NODE");

        Supplier<CommandResult> addRegistration = () -> {
            CommandResult result;
            if (timingNode.functionalState() != FunctionalState.OPEN) {
                result = CommandResult.nodeNotOpen();
            } else {
                LogBookCommand<TimingData> command =
                        LogBookCommands.commitAutomaticRegistration(
                                timingNode.locationId(),
                                registrationId,
                                effectiveTime,
                                TimingData.TagSource.API,
                                registrationTimeSource);
                TimingData timingData = logBook.process(command);
                result = CommandResult.committed(timingData);
            }
            return result;
        };

        return timingNode.processLocked(addRegistration);
    }

    /**
     * Commits one normal manual registration with client-supplied effective
     * time and client-side time-selection classification.
     *
     * <p>The TimingNode operation lock keeps the OPEN check and current LocationId
     * together with the ordered LogBook commit.</p>
     */
    public CommandResult addManualRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource timeSource) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(effectiveTime != null, "effectiveTime must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");
        Supplier<CommandResult> addRegistration = () -> {
            CommandResult result;
            if (timingNode.functionalState() != FunctionalState.OPEN) {
                result = CommandResult.nodeNotOpen();
            } else {
                LogBookCommand<TimingData> command =
                        LogBookCommands.commitManualRegistration(
                                timingNode.locationId(),
                                registrationId,
                                effectiveTime,
                                timeSource);
                TimingData timingData = logBook.process(command);
                result = CommandResult.committed(timingData);
            }
            return result;
        };

        return timingNode.processLocked(addRegistration);
    }

    /**
     * Appends one registration REV record using caller-supplied original
     * registration semantics.
     *
     * <p>This boundary deliberately does not search or fold LogBook history.
     * Higher application/business logic selects the record family and original
     * values. The LogBook command is processed inside the TimingNode operation lock
     * so presentation-triggered writes use one consistent node-scoped mutation scope.</p>
     */
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

        LogBookCommand<TimingData> command;
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

        Supplier<CommandResult> processRevocation =
                () -> CommandResult.committed(logBook.process(command));
        return timingNode.processLocked(processRevocation);
    }

    /**
     * Returns the number of committed records in this node LogBook.
     */
    public int logBookCount() {
        return logBook.process(LogBookQueries.timingDataCount());
    }

    /**
     * Visits a bounded committed LogBook range in source order.
     *
     * @return total committed LogBook count from the same ordered read
     */
    public int visitLogBookFrom(
            long fromSequence,
            int limit,
            Consumer<TimingData> visitor) {
        return logBook.process(
                LogBookQueries.visitTimingDataRange(
                        fromSequence,
                        limit,
                        visitor));
    }

    /**
     * Visits a bounded newest LogBook range in source order.
     *
     * @return total committed LogBook count from the same ordered read
     */
    public int visitLatestLogBook(
            int limit,
            Consumer<TimingData> visitor) {
        return logBook.process(
                LogBookQueries.visitLatestTimingData(limit, visitor));
    }

    /**
     * Publishes only property changes represented by {@link TimingNodeStatus}.
     *
     * <p>Operational readiness is a separate TimingNode property. Until it is
     * part of the presentation status contract, changing it must not emit an
     * unchanged client-visible functional/location snapshot.</p>
     */
    private void publishVisibleStatusChange(PropertyUpdate update) {

        if (update.contains(TimingNode.FUNCTIONAL_STATE)
                || update.contains(TimingNode.LOCATION_ID)) {
            statusChangedEvent.emit(status());
        }
    }
}

