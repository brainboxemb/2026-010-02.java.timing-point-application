package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Published CURRENT status view and status-change event for one LogBook.
 *
 * <p>Mutable lifecycle/location facts remain in {@link LogBookState}. This
 * object owns only the safely published immutable Status snapshot, availability
 * checks, status comparison, diagnostics and the post-fact status event.</p>
 */
final class LogBookStatus {
    private static final Logger LOG = LoggerFactory.getLogger(LogBookStatus.class);

    private final NodeId timingNodeId;
    private final LogBookDataLane dataLane;
    private final Event<Status> statusChangedEvent = new Event<Status>();

    /** Latest activation-complete immutable Status visible to CURRENT queries. */
    private volatile Status publishedStatus;

    /** Creates the status publication owner for one LogBook Data Lane. */
    LogBookStatus(NodeId timingNodeId, LogBookDataLane dataLane) {
        checkArgument(timingNodeId != null, "timingNodeId must not be null");
        checkArgument(dataLane != null, "dataLane must not be null");
        this.timingNodeId = timingNodeId;
        this.dataLane = dataLane;
    }

    /** Returns the externally subscribable post-fact status event. */
    EventSource<Status> statusChangedEvent() {
        return statusChangedEvent;
    }

    /** Makes CURRENT status unavailable after LogBook deactivation. */
    void clear() {
        publishedStatus = null;
    }

    /** Returns the latest safely published CURRENT status or explains why none is available. */
    Status current() {
        SerialLane.State state = dataLane.state();
        if (state == SerialLane.State.FAILED) {
            throw new OperationException(
                    OperationException.Reason.FAILED,
                    "status CURRENT result is unavailable because the Data Lane failed",
                    dataLane.failure());
        }
        if (state != SerialLane.State.RUNNING) {
            throw new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    "status CURRENT result is unavailable because the LogBook component is not active");
        }

        Status status = publishedStatus;
        if (status == null) {
            throw new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    "status CURRENT result is unavailable because persisted TimingData is not loaded yet");
        }
        return status;
    }

    /** Replaces the immutable snapshot used by CURRENT queries. */
    void publishCurrent(Status status) {
        checkArgument(status != null, "status must not be null");
        publishedStatus = status;
    }

    /** Publishes the first Status fact after persisted data reconstruction finishes. */
    void publishInitial(Status status) {
        publishCurrent(status);

        LOG.info(
                "TimingNode {} initial status available: state {}, location {}, problems {}",
                timingNodeId.value(),
                status.state(),
                status.hasLocation() ? status.locationId() : "-",
                status.problems().size());

        Event.DeliveryReport delivery = statusChangedEvent.emit(status);
        if (!delivery.successful()) {
            LOG.warn(
                    "TimingNode initial status published but "
                            + delivery.failureCount()
                            + " status listener(s) failed for "
                            + timingNodeId.value(),
                    delivery.failures().get(0));
        }
    }

    /** Emits a status event only when the effective immutable Status changed. */
    void publishChanged(Status before, Status after) {
        checkArgument(before != null, "before must not be null");
        checkArgument(after != null, "after must not be null");

        if (sameStatus(before, after)) {
            return;
        }

        LOG.info(
                "TimingNode {} status changed: state {} -> {}, location {} -> {}, problems {} -> {}",
                timingNodeId.value(),
                before.state(),
                after.state(),
                before.hasLocation() ? before.locationId() : "-",
                after.hasLocation() ? after.locationId() : "-",
                before.problems().size(),
                after.problems().size());

        Event.DeliveryReport delivery = statusChangedEvent.emit(after);
        if (!delivery.successful()) {
            LOG.warn(
                    "TimingNode status changed but "
                            + delivery.failureCount()
                            + " status listener(s) failed for "
                            + timingNodeId.value(),
                    delivery.failures().get(0));
        }
    }

    /** Compares immutable Status values without depending on object identity. */
    private static boolean sameStatus(Status left, Status right) {
        if (!left.timingNodeId().equals(right.timingNodeId())) {
            return false;
        }
        if (left.state() != right.state()) {
            return false;
        }
        if (left.timingDataTailRepaired() != right.timingDataTailRepaired()) {
            return false;
        }
        if (!left.problems().equals(right.problems())) {
            return false;
        }
        if (!left.hasLocation()) {
            return !right.hasLocation();
        }
        return right.hasLocation() && left.locationId().equals(right.locationId());
    }
}
