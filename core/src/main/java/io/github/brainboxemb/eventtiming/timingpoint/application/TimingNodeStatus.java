package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.ErrorCode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.FunctionalState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationalState;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * Transport-independent read model for one TimingNode.
 *
 * <p>This object is a presentation snapshot only. Domain functional state,
 * operational state and LocationId remain separate values owned by TimingNode.</p>
 */
public final class TimingNodeStatus {
    private final NodeId timingNodeId;
    private final FunctionalState functionalState;
    private final OperationalState operationalState;
    private final LocationId locationId;
    private final ErrorCode errorCode;
    private final String errorMessage;

    /** Creates one presentation snapshot from separate TimingNode state values. */
    public static TimingNodeStatus current(
            NodeId timingNodeId,
            FunctionalState functionalState,
            OperationalState operationalState,
            LocationId locationId,
            ErrorCode errorCode,
            String errorMessage) {

        checkArgument(timingNodeId != null, "timingNodeId must not be null");
        checkArgument(functionalState != null, "functionalState must not be null");
        checkArgument(operationalState != null, "operationalState must not be null");

        boolean hasProblem = errorCode != null;
        checkArgument(
                hasProblem == (errorMessage != null && !errorMessage.trim().isEmpty()),
                "errorCode and errorMessage must either both be present or both be absent");
        checkArgument(
                operationalState == OperationalState.ERROR || !hasProblem,
                "problem detail requires operational ERROR state");

        return new TimingNodeStatus(
                timingNodeId,
                functionalState,
                operationalState,
                locationId,
                errorCode,
                errorMessage == null ? null : errorMessage.trim());
    }

    private TimingNodeStatus(
            NodeId timingNodeId,
            FunctionalState functionalState,
            OperationalState operationalState,
            LocationId locationId,
            ErrorCode errorCode,
            String errorMessage) {

        this.timingNodeId = timingNodeId;
        this.functionalState = functionalState;
        this.operationalState = operationalState;
        this.locationId = locationId;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public NodeId timingNodeId() {
        return timingNodeId;
    }

    public FunctionalState functionalState() {
        return functionalState;
    }

    public OperationalState operationalState() {
        return operationalState;
    }

    public boolean hasLocation() {
        return locationId != null;
    }

    public LocationId locationId() {
        checkState(locationId != null, "TimingNode has no current location");
        return locationId;
    }

    public boolean hasProblem() {
        return errorCode != null;
    }

    public ErrorCode errorCode() {
        checkState(hasProblem(), "TimingNode has no operational problem");
        return errorCode;
    }

    public String errorMessage() {
        checkState(hasProblem(), "TimingNode has no operational problem");
        return errorMessage;
    }
}
