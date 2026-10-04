package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;
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

    private final TimingNode timingNode;
    private final Event<TimingNodeStatus> statusChangedEvent = new Event<>();

    TimingNodeProxy(TimingNode timingNode) {
        if (timingNode == null) {
            throw new IllegalArgumentException("timingNode must not be null");
        }
        this.timingNode = timingNode;
        timingNode.statusChangedEvent().subscribe(this::updateStatus);
    }

    /** Returns the current authoritative node status. */
    public TimingNodeStatus status() {
        return timingNodeStatus(
                timingNode.query(TimingNodeQueries.status()));
    }

    /** Opens the node at the requested LocationId. */
    public OpenResult open(LocationId locationId) {
        return timingNode.invoke(TimingNodeCommands.open(locationId));
    }

    /** Closes the node. */
    public CloseResult close() {
        return timingNode.invoke(TimingNodeCommands.close());
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
                return timingNode.invoke(
                        TimingNodeCommands.addAutomaticRegistration(
                                registrationId,
                                time));
            default:
                throw new IllegalArgumentException(
                        "Unsupported automatic registration action " + action);
        }
    }

    /** Returns the number of committed records in this node LogBook. */
    public int logBookCount() {
        return timingNode.query(TimingNodeQueries.timingDataCount());
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
        return timingNode.query(
                TimingNodeQueries.visitTimingDataRange(
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
        return timingNode.query(
                TimingNodeQueries.visitLatestTimingData(limit, visitor));
    }

    /** Returns the subscription-only authoritative status-change event. */
    public EventSource<TimingNodeStatus> statusChangedEvent() {
        return statusChangedEvent;
    }

    /** Returns the subscription-only committed TimingData event. */
    public EventSource<TimingData> timingDataCommittedEvent() {
        return timingNode.timingDataCommittedEvent();
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
                status.lifecycle(),
                status.locationId(),
                status.problems());
    }
}
