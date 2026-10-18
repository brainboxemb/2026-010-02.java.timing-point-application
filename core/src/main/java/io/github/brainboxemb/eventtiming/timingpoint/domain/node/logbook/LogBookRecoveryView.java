package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Safely published CURRENT recovery view for one LogBook.
 *
 * <p>This object owns only the immutable recovery result and its availability
 * checks. TimingNode OPEN/CLOSED state is not represented here.</p>
 */
final class LogBookRecoveryView {
    private static final Logger LOG = LoggerFactory.getLogger(LogBookRecoveryView.class);

    private final NodeId timingNodeId;
    private final BooleanSupplier active;
    private final Event<LogBookRecoveryStatus> recoveryStatusChangedEvent =
            new Event<LogBookRecoveryStatus>();

    /** Latest completed recovery result visible to CURRENT queries. */
    private volatile LogBookRecoveryStatus publishedRecoveryStatus;

    LogBookRecoveryView(NodeId timingNodeId, BooleanSupplier active) {
        failIfInvalidArgument(timingNodeId != null, "timingNodeId must not be null");
        failIfInvalidArgument(active != null, "active must not be null");
        this.timingNodeId = timingNodeId;
        this.active = active;
    }

    /** Returns the event published when the startup recovery attempt completes. */
    EventSource<LogBookRecoveryStatus> recoveryStatusChangedEvent() {
        return recoveryStatusChangedEvent;
    }

    /** Makes the CURRENT recovery result unavailable after LogBook deactivation. */
    void clear() {
        publishedRecoveryStatus = null;
    }

    /** Returns the latest completed recovery result or explains why none is available. */
    LogBookRecoveryStatus current() {
        if (!active.getAsBoolean()) {
            throw new IllegalStateException(
                    "recovery status CURRENT result is unavailable because the LogBook is not active");
        }

        LogBookRecoveryStatus status = publishedRecoveryStatus;
        if (status == null) {
            throw new IllegalStateException(
                    "recovery status CURRENT result is unavailable because persisted TimingData is not loaded yet");
        }
        return status;
    }

    /** Publishes the completed startup recovery result. */
    void publishInitial(LogBookRecoveryStatus status) {
        failIfInvalidArgument(status != null, "status must not be null");
        publishedRecoveryStatus = status;

        LOG.info(
                "TimingNode {} LogBook recovery completed: repairedIncompleteTail={}, failed={}",
                timingNodeId.value(),
                status.repairedIncompleteTail(),
                status.failed());

        recoveryStatusChangedEvent.emit(status);
    }
}
