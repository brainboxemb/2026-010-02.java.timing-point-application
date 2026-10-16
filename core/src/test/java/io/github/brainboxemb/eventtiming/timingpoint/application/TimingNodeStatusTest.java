package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimingNodeStatusTest {
    @Test
    public void exposesOperationalTimingNodeStatus() {
        TimingNodeStatus status = TimingNodeStatus.operational(
                new NodeId("A"),
                TimingNodeTypes.State.CLOSED,
                null);

        assertEquals("A", status.timingNodeId().value());
        assertFalse(status.error());
        assertEquals(TimingNodeTypes.State.CLOSED, status.state());
    }

    @Test
    public void exposesErrorTimingNodeStatus() {
        TimingNodeStatus status = TimingNodeStatus.error(
                new NodeId("A"),
                TimingNodeTypes.ErrorCode.TIMING_DATA_LOAD_FAILED,
                "load failed");

        assertTrue(status.error());
        assertEquals(
                TimingNodeTypes.ErrorCode.TIMING_DATA_LOAD_FAILED,
                status.errorCode());
        assertEquals("load failed", status.errorMessage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingNodeId() {
        TimingNodeStatus.operational(
                null,
                TimingNodeTypes.State.CLOSED,
                null);
    }
}
