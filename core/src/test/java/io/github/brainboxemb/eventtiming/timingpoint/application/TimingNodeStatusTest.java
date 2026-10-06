package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TimingNodeStatusTest {
    @Test
    public void exposesCurrentTimingNodeStatus() {
        TimingNodeStatus status = new TimingNodeStatus(
                new NodeId("TN-01"),
                TimingNodeTypes.State.CLOSED);

        assertEquals("TN-01", status.timingNodeId().value());
        assertEquals(TimingNodeTypes.State.CLOSED, status.state());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingNodeId() {
        new TimingNodeStatus(null, TimingNodeTypes.State.CLOSED);
    }
}
