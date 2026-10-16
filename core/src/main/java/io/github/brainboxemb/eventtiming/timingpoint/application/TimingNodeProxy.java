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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.ErrorStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.Optional;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Presentation-facing application proxy for one TimingNode.
 *
 * <p>The proxy gives presentation adapters explicit node context while keeping the
 * Domain component behind the Application boundary. It maps presentation intent to
 * typed TimingNode commands/queries and exposes node-scoped application events.</p>
 */
public final class TimingNodeProxy {

    /** Domain operation-status input mapped to presentation-facing status values. */
    private static final EventInput<TimingNodeProxy, Status> DOMAIN_STATUS_CHANGED =
            EventInput.of(
                    (proxy, source) -> source.subscribe(proxy::updateStatus));

    /** Domain error-status input mapped to presentation-facing status values. */
    private static final EventInput<TimingNodeProxy, ErrorStatus> ERROR_STATUS_CHANGED =
            EventInput.of(
                    (proxy, source) -> source.subscribe(proxy::updateErrorStatus));

    /** Registration record family supplied by a higher interpreting layer. */
    public enum RegistrationRecordType {
        AUTO_REG,
        MAN_REG
    }

    private final NodeId timingNodeId;
    private final TimingNode timingNode;
    private final LogBookAccessor logBook;
    private final Event<TimingNodeStatus> statusChanged = new Event<>();

    /** Presentation-facing node-status source. */
    public final EventSource<TimingNodeStatus> STATUS_CHANGED = statusChanged;

    /** Presentation-facing post-commit TimingData source. */
    public final EventSource<TimingData> NEW_DATA_ENTRY;

    TimingNodeProxy(TimingNode timingNode) {

        checkArgument(timingNode != null, "timingNode must not be null");
        this.timingNodeId = timingNode.timingNodeId();
        this.timingNode = timingNode;
        this.logBook = timingNode.logBook();
        this.NEW_DATA_ENTRY = timingNode.TIMING_DATA_COMMITTED;
        DOMAIN_STATUS_CHANGED.bind(this, timingNode.STATUS_CHANGED);
        ERROR_STATUS_CHANGED.bind(this, timingNode.ERROR_STATUS_CHANGED);
    }

    /** Returns this node's ID. */
    public NodeId timingNodeId() {
        return timingNodeId;
    }

    /** Returns the TimeSource configured for this node. This lookup does not block. */
    public TimeSource timeSource() {
        return timingNode.timeSource();
    }

    /** Returns the current presentation status for this TimingNode. */
    public TimingNodeStatus status() {
        Optional<ErrorStatus> error = timingNode.errorStatus();
        if (error.isPresent()) {
            return errorStatus(error.get());
        }
        return statusValue(timingNode.status());
    }

    /**
     * Opens the node at the requested LocationId.
     *
     * @throws OperationException when the node operation cannot return its normal result
     */
    public OpenResult open(LocationId locationId) throws OperationException {
        return timingNode.open(locationId);
    }

    /**
     * Closes the node.
     *
     * @throws OperationException when the node operation cannot return its normal result
     */
    public CloseResult close() throws OperationException {
        return timingNode.close();
    }

    /**
     * Adds one custom automatic registration using the supplied effective time.
     *
     * <p>This call blocks on the TimingNode/LogBook commit path. The caller also
     * identifies whether the supplied time came from this node's TimeSource or
     * was explicitly supplied by an API client.</p>
     *
     * @throws OperationException when the node operation cannot return its normal result
     */
    public RegistrationResult addCustomAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingData.AutomaticTimeSource registrationTimeSource)
            throws OperationException {
        return timingNode.addCustomAutomaticRegistration(
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

        checkArgument(registrationId != null, "registrationId must not be null");
        checkArgument(effectiveTime != null, "effectiveTime must not be null");
        checkArgument(timeSource != null, "timeSource must not be null");
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

        checkArgument(recordType != null, "recordType must not be null");
        checkArgument(originalLocationId != null, "originalLocationId must not be null");
        checkArgument(registrationId != null, "registrationId must not be null");
        checkArgument(originalTime != null, "originalTime must not be null");

        switch (recordType) {
            case AUTO_REG:
                checkArgument(originalTimeSource == null, "AUTO_REG revoke must not carry originalTimeSource");
                return logBook.process(
                        LogBookCommands.revokeAutomaticRegistration(
                                originalLocationId,
                                registrationId,
                                originalTime));
            case MAN_REG:
                checkArgument(originalTimeSource != null, "MAN_REG revoke requires originalTimeSource");
                return logBook.process(
                        LogBookCommands.revokeManualRegistration(
                                originalLocationId,
                                registrationId,
                                originalTime,
                                originalTimeSource));
            default:
                throw new IllegalArgumentException(
                        "Unsupported registration record type " + recordType);
        }
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

    private void updateStatus(Status status) {

        statusChanged.emit(statusValue(status));
    }

    private void updateErrorStatus(ErrorStatus error) {

        statusChanged.emit(errorStatus(error));
    }

    private TimingNodeStatus statusValue(Status status) {

        return TimingNodeStatus.operational(
                timingNodeId,
                status.state,
                status.locationId);
    }

    private TimingNodeStatus errorStatus(ErrorStatus error) {

        return TimingNodeStatus.error(
                timingNodeId,
                error.code,
                error.message);
    }
}
