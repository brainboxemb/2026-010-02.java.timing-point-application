package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.FunctionalState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationalState;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/** Current client-visible status of one TimingNode. */
public final class TimingNodeStatus {
    private final NodeId timingNodeId;
    private final FunctionalState state;
    private final OperationalState opState;
    private final LocationId locationId;

    /** Creates one current TimingNode status. */
    public static TimingNodeStatus current(
            NodeId timingNodeId,
            FunctionalState state,
            OperationalState opState,
            LocationId locationId) {

        checkArgument(timingNodeId != null, "timingNodeId must not be null");
        checkArgument(state != null, "state must not be null");
        checkArgument(opState != null, "opState must not be null");
        return new TimingNodeStatus(timingNodeId, state, opState, locationId);
    }

    private TimingNodeStatus(
            NodeId timingNodeId,
            FunctionalState state,
            OperationalState opState,
            LocationId locationId) {

        this.timingNodeId = timingNodeId;
        this.state = state;
        this.opState = opState;
        this.locationId = locationId;
    }

    public NodeId timingNodeId() {
        return timingNodeId;
    }

    public FunctionalState state() {
        return state;
    }

    public OperationalState opState() {
        return opState;
    }

    public boolean hasLocation() {
        return locationId != null;
    }

    public LocationId locationId() {
        checkState(locationId != null, "TimingNode has no current location");
        return locationId;
    }


}

