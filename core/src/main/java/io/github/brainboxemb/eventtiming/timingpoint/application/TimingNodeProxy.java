package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookTestControl;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.function.Consumer;

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
        this.NEW_DATA_ENTRY = timingNode.TIMING_DATA_COMMITTED;
        timingNode.PROPERTIES.CHANGED.subscribe(ignored -> publishStatus());
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
     *
     * @throws OperationException when the node is not available for opening
     */
    public OpenResult open(LocationId locationId) throws OperationException {
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
     * <p>This call blocks until the TimingNode returns the processed operation result.
     * The caller also identifies whether the supplied time came from this node's
     * TimeSource or was explicitly supplied by an API client.</p>
     *
     * @throws OperationException when the node operation cannot return its normal result
     */
    public RegistrationResult addTestAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingData.AutomaticTimeSource registrationTimeSource)
            throws OperationException {
        return timingNode.addTestAutomaticRegistration(
                registrationId,
                effectiveTime,
                registrationTimeSource);
    }

    /**
     * Commits one normal manual registration with client-supplied effective
     * time and client-side time-selection classification.
     *
     * @throws OperationException when the node operation cannot return its normal result
     */
    public RegistrationResult addManualRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource timeSource)
            throws OperationException {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(effectiveTime != null, "effectiveTime must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");
        return timingNode.addManualRegistration(
                registrationId,
                effectiveTime,
                timeSource);
    }

    /**
     * Appends one registration REV record using caller-supplied original
     * registration semantics.
     *
     * <p>This boundary deliberately does not search or fold LogBook history.
     * Higher application/business logic selects the record family and original
     * values; TimingNode owns only ordered commit bookkeeping.</p>
     *
     * @throws OperationException when the ordered LogBook operation cannot return its normal result
     */
    public RegistrationResult revokeRegistration(
            RegistrationRecordType recordType,
            LocationId originalLocationId,
            RegistrationId registrationId,
            TimingTimestamp originalTime,
            ManualTimeSource originalTimeSource)
            throws OperationException {

        failIfInvalidArgument(recordType != null, "recordType must not be null");
        failIfInvalidArgument(originalLocationId != null, "originalLocationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(originalTime != null, "originalTime must not be null");

        RegistrationResult result;
        switch (recordType) {
            case AUTO_REG:
                failIfInvalidArgument(originalTimeSource == null, "AUTO_REG revoke must not carry originalTimeSource");
                result = logBook.process(
                        LogBookCommands.revokeAutomaticRegistration(
                                originalLocationId,
                                registrationId,
                                originalTime));
                break;
            case MAN_REG:
                failIfInvalidArgument(originalTimeSource != null, "MAN_REG revoke requires originalTimeSource");
                result = logBook.process(
                        LogBookCommands.revokeManualRegistration(
                                originalLocationId,
                                registrationId,
                                originalTime,
                                originalTimeSource));
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported registration record type " + recordType);
        }
        return result;
    }

    /**
     * Returns the number of committed records in this node LogBook.
     *
     * @throws OperationException when the ordered LogBook read cannot return its result
     */
    public int logBookCount() throws OperationException {
        return logBook.process(LogBookQueries.timingDataCount());
    }

    /**
     * Visits a bounded committed LogBook range in source order.
     *
     * @return total committed LogBook count from the same ordered read
     * @throws OperationException when the ordered LogBook read cannot return its result
     */
    public int visitLogBookFrom(
            long fromSequence,
            int limit,
            Consumer<TimingData> visitor)
            throws OperationException {
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
     * @throws OperationException when the ordered LogBook read cannot return its result
     */
    public int visitLatestLogBook(
            int limit,
            Consumer<TimingData> visitor)
            throws OperationException {
        return logBook.process(
                LogBookQueries.visitLatestTimingData(limit, visitor));
    }

    /** Publishes one client status after one committed property-container update. */
    private void publishStatus() {

        statusChangedEvent.emit(status());
    }
}

