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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Presentation-facing application proxy for one TimingNode.
 *
 * <p>The proxy gives presentation adapters explicit node context while keeping the
 * Domain component behind the Application boundary. It maps presentation intent to
 * typed TimingNode commands/queries and exposes node-scoped application events.</p>
 */
public final class TimingNodeProxy {
    private static final Logger LOG = LoggerFactory.getLogger(TimingNodeProxy.class);

    /** Automatic-registration actions currently supported by this application boundary. */
    public enum AutomaticRegistrationAction {
        ADD
    }

    /** Registration record family supplied by a higher interpreting layer. */
    public enum RegistrationRecordType {
        AUTO_REG,
        MAN_REG
    }

    private final NodeId timingNodeId;
    private final LogBookAccessor logBook;
    private final Event<TimingNodeStatus> statusChangedEvent = new Event<>();

    TimingNodeProxy(TimingNode timingNode) {
        if (timingNode == null) {
            throw new IllegalArgumentException("timingNode must not be null");
        }
        this.timingNodeId = timingNode.timingNodeId();
        this.logBook = timingNode.logBook();
        logBook.statusChangedEvent().subscribe(this::updateStatus);
    }

    /** Returns the stable identity of the proxied TimingNode. */
    public NodeId timingNodeId() {
        return timingNodeId;
    }

    /** Returns the current authoritative node status. */
    public TimingNodeStatus status() {
        return timingNodeStatus(
                logBook.process(
                        LogBookQueries.status(),
                        ReadConsistency.CURRENT));
    }

    /** Opens the node at the requested LocationId. */
    public OpenResult open(LocationId locationId) {
        return logBook.process(LogBookCommands.open(locationId));
    }

    /** Closes the node. */
    public CloseResult close() {
        return logBook.process(LogBookCommands.close());
    }

    /**
     * Applies one semantic automatic-registration action.
     *
     * <p>The presentation caller supplies the action, registration identity and
     * registration time. TimingNode remains responsible for active location,
     * sequence, recordedAt, persistence and the committed TimingData record.</p>
     */
    public RegistrationResult applyAutomaticRegistration(
            AutomaticRegistrationAction action,
            RegistrationId registrationId,
            TimingTimestamp time) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        switch (action) {
            case ADD:
                return logBook.process(
                        LogBookCommands.addAutomaticRegistration(
                                registrationId,
                                time));
            default:
                throw new IllegalArgumentException(
                        "Unsupported automatic registration action " + action);
        }
    }

    /** Engineering-only accepted registration with an explicit API timestamp. */
    public RegistrationResult simulateAutomaticRegistration(
            RegistrationId registrationId, TimingTimestamp time) {
        return logBook.process(
                LogBookCommands.simulateAutomaticRegistration(registrationId, time));
    }

    /** Engineering-only accepted registration using this node's own TimeSource. */
    public RegistrationResult simulateAutomaticRegistrationNow(
            RegistrationId registrationId) {
        return logBook.process(
                LogBookCommands.simulateAutomaticRegistrationNow(registrationId));
    }

    /**
     * Commits one normal manual registration with client-supplied effective
     * time and client-side time-selection classification.
     */
    public RegistrationResult addManualRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource timeSource) {
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "registrationId must not be null");
        }
        if (effectiveTime == null) {
            throw new IllegalArgumentException(
                    "effectiveTime must not be null");
        }
        if (timeSource == null) {
            throw new IllegalArgumentException(
                    "timeSource must not be null");
        }
        return logBook.process(
                LogBookCommands.commitManualRegistration(
                        registrationId,
                        effectiveTime,
                        timeSource));
    }

    /**
     * Appends one registration REV record using caller-supplied original
     * registration semantics.
     *
     * <p>This boundary deliberately does not search or fold LogBook history.
     * Higher application/business logic selects the record family and original
     * values; TimingNode owns only ordered commit bookkeeping.</p>
     */
    public RegistrationResult revokeRegistration(
            RegistrationRecordType recordType,
            LocationId originalLocationId,
            RegistrationId registrationId,
            TimingTimestamp originalTime,
            ManualTimeSource originalTimeSource) {
        if (recordType == null) {
            throw new IllegalArgumentException(
                    "recordType must not be null");
        }
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

        switch (recordType) {
            case AUTO_REG:
                if (originalTimeSource != null) {
                    throw new IllegalArgumentException(
                            "AUTO_REG revoke must not carry originalTimeSource");
                }
                return logBook.process(
                        LogBookCommands.revokeAutomaticRegistration(
                                originalLocationId,
                                registrationId,
                                originalTime));
            case MAN_REG:
                if (originalTimeSource == null) {
                    throw new IllegalArgumentException(
                            "MAN_REG revoke requires originalTimeSource");
                }
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

    /** Returns the number of committed records in this node LogBook. */
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
     * Returns node Status publications for Presentation.
     *
     * <p>The first event may be the TimingNode's post-recovery initial Status;
     * later events represent effective status changes.</p>
     */
    public EventSource<TimingNodeStatus> statusChangedEvent() {
        return statusChangedEvent;
    }

    /** Returns the subscription-only post-commit new-data event. */
    public EventSource<TimingData> newDataEntryEvent() {
        return logBook.newDataEntryEvent();
    }

    private void updateStatus(Status status) {
        TimingNodeStatus update = timingNodeStatus(status);
        Event.DeliveryReport delivery = statusChangedEvent.emit(update);
        if (!delivery.successful()) {
            LOG.warn(
                    "TimingNode status changed but "
                            + delivery.failureCount()
                            + " status listener(s) failed for "
                            + update.timingNodeId().value(),
                    delivery.failures().get(0));
        }
    }

    private static TimingNodeStatus timingNodeStatus(Status status) {
        return new TimingNodeStatus(
                status.timingNodeId(),
                status.state(),
                status.locationId(),
                status.problems());
    }
}
