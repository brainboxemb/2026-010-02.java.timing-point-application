package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.ErrorCode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/** Transport-independent current TimingNode status used by presentation adapters. */
public final class TimingNodeStatus {
    private final NodeId timingNodeId;
    private final State state;
    private final LocationId locationId;
    private final ErrorCode errorCode;
    private final String errorMessage;

    /** Creates an operational OPEN/CLOSED status. */
    public static TimingNodeStatus operational(
            NodeId timingNodeId,
            State state,
            LocationId locationId) {
        checkArgument(timingNodeId != null, "timingNodeId must not be null");
        checkArgument(state != null, "state must not be null");
        return new TimingNodeStatus(timingNodeId, state, locationId, null, null);
    }

    /** Creates an error status. */
    public static TimingNodeStatus error(
            NodeId timingNodeId,
            ErrorCode errorCode,
            String errorMessage) {
        checkArgument(timingNodeId != null, "timingNodeId must not be null");
        checkArgument(errorCode != null, "errorCode must not be null");
        checkArgument(
                errorMessage != null && !errorMessage.trim().isEmpty(),
                "errorMessage must not be blank");
        return new TimingNodeStatus(
                timingNodeId,
                null,
                null,
                errorCode,
                errorMessage.trim());
    }

    private TimingNodeStatus(
            NodeId timingNodeId,
            State state,
            LocationId locationId,
            ErrorCode errorCode,
            String errorMessage) {
        this.timingNodeId = timingNodeId;
        this.state = state;
        this.locationId = locationId;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public NodeId timingNodeId() {
        return timingNodeId;
    }

    public boolean error() {
        return errorCode != null;
    }

    public State state() {
        checkState(!error(), "error status has no operational state");
        return state;
    }

    public boolean hasLocation() {
        return !error() && locationId != null;
    }

    public LocationId locationId() {
        checkState(!error(), "error status has no location");
        return locationId;
    }

    public ErrorCode errorCode() {
        checkState(error(), "operational status has no error code");
        return errorCode;
    }

    public String errorMessage() {
        checkState(error(), "operational status has no error message");
        return errorMessage;
    }
}
