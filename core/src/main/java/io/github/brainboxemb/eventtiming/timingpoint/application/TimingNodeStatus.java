package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.FunctionalState;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/** Current functional status of one TimingNode for operator-facing clients. */
public final class TimingNodeStatus {
    private final NodeId timingNodeId;
    private final FunctionalState state;
    private final LocationId locationId;

    /** Creates one current TimingNode status. */
    public static TimingNodeStatus current(
            NodeId timingNodeId,
            FunctionalState state,
            LocationId locationId) {

        checkArgument(timingNodeId != null, "timingNodeId must not be null");
        checkArgument(state != null, "state must not be null");
        return new TimingNodeStatus(timingNodeId, state, locationId);
    }

    private TimingNodeStatus(
            NodeId timingNodeId,
            FunctionalState state,
            LocationId locationId) {

        this.timingNodeId = timingNodeId;
        this.state = state;
        this.locationId = locationId;
    }

    public NodeId timingNodeId() {
        return timingNodeId;
    }

    public FunctionalState state() {
        return state;
    }

    public boolean hasLocation() {
        return locationId != null;
    }

    public LocationId locationId() {
        checkState(locationId != null, "TimingNode has no current location");
        return locationId;
    }
}
