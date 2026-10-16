package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TimingNodeStatusTest {
    @Test
    public void exposesStateAndLocation() {
        TimingNodeStatus status = TimingNodeStatus.current(
                new NodeId("A"),
                TimingNodeTypes.FunctionalState.OPEN,
                TimingNodeTypes.OperationalState.OK,
                new LocationId(24));

        assertEquals("A", status.timingNodeId().value());
        assertEquals(
                TimingNodeTypes.FunctionalState.OPEN,
                status.state());
        assertEquals(TimingNodeTypes.OperationalState.OK, status.opState());
        assertEquals(new LocationId(24), status.locationId());
    }

    @Test
    public void closedStatusMayHaveNoLocation() {
        TimingNodeStatus status = TimingNodeStatus.current(
                new NodeId("A"),
                TimingNodeTypes.FunctionalState.CLOSED,
                TimingNodeTypes.OperationalState.INIT,
                null);

        assertFalse(status.hasLocation());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingNodeId() {
        TimingNodeStatus.current(
                null,
                TimingNodeTypes.FunctionalState.CLOSED,
                TimingNodeTypes.OperationalState.INIT,
                null);
    }
}
