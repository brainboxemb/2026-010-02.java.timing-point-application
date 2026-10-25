package oss.relay.eventtiming.timingpoint.application;

import oss.relay.eventtiming.timingdata.TimingDataTypes.LocationId;
import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingpoint.domain.node.TimingNodeTypes;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TimingNodeStatusTest {
    @Test
    public void exposesStateAndLocation() {
        TimingNodeStatus status = TimingNodeStatus.current(
                new NodeId("A"),
                TimingNodeTypes.FunctionalState.OPEN,
                new LocationId(24));

        assertEquals("A", status.timingNodeId().value());
        assertEquals(
                TimingNodeTypes.FunctionalState.OPEN,
                status.state());
        assertEquals(new LocationId(24), status.locationId());
    }

    @Test
    public void closedStatusMayHaveNoLocation() {
        TimingNodeStatus status = TimingNodeStatus.current(
                new NodeId("A"),
                TimingNodeTypes.FunctionalState.CLOSED,
                null);

        assertFalse(status.hasLocation());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingNodeId() {
        TimingNodeStatus.current(
                null,
                TimingNodeTypes.FunctionalState.CLOSED,
                null);
    }
}
