package oss.relay.timing.timingpoint.application;

import oss.relay.timing.timingdata.TimingDataTypes.LocationId;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingpoint.domain.node.TimingNodeTypes.FunctionalState;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfIllegalState;

/** Current client-visible status of one TimingNode. */
public final class TimingNodeStatus {
    private final NodeId timingNodeId;
    private final FunctionalState state;
    private final LocationId locationId;

    /** Creates one current TimingNode status. */
    public static TimingNodeStatus current(
            NodeId timingNodeId,
            FunctionalState state,
            LocationId locationId) {

        failIfInvalidArgument(timingNodeId != null, "timingNodeId must not be null");
        failIfInvalidArgument(state != null, "state must not be null");
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
        failIfIllegalState(locationId != null, "TimingNode has no current location");
        return locationId;
    }

}

