package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TimingNodeStatusTest {
    @Test
    public void exposesSeparateFunctionalAndOperationalState() {
        TimingNodeStatus status = TimingNodeStatus.current(
                new NodeId("A"),
                TimingNodeTypes.FunctionalState.CLOSED,
                TimingNodeTypes.OperationalState.OK,
                null,
                null,
                null);

        assertEquals("A", status.timingNodeId().value());
        assertEquals(
                TimingNodeTypes.FunctionalState.CLOSED,
                status.functionalState());
        assertEquals(
                TimingNodeTypes.OperationalState.OK,
                status.operationalState());
        assertFalse(status.hasProblem());
    }

    @Test
    public void operationalErrorRetainsFunctionalState() {
        TimingNodeStatus status = TimingNodeStatus.current(
                new NodeId("A"),
                TimingNodeTypes.FunctionalState.OPEN,
                TimingNodeTypes.OperationalState.ERROR,
                null,
                TimingNodeTypes.ErrorCode.TIMING_DATA_LOAD_FAILED,
                "load failed");

        assertEquals(
                TimingNodeTypes.FunctionalState.OPEN,
                status.functionalState());
        assertEquals(
                TimingNodeTypes.OperationalState.ERROR,
                status.operationalState());
        assertTrue(status.hasProblem());
        assertEquals(
                TimingNodeTypes.ErrorCode.TIMING_DATA_LOAD_FAILED,
                status.errorCode());
        assertEquals("load failed", status.errorMessage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingNodeId() {
        TimingNodeStatus.current(
                null,
                TimingNodeTypes.FunctionalState.CLOSED,
                TimingNodeTypes.OperationalState.OK,
                null,
                null,
                null);
    }
}
