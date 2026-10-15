package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SystemStatusTest {

    @Test
    public void currentStatusTracksConfiguredNodeOrderAndCompleteness() {

        NodeId firstId = new NodeId("A");
        NodeId secondId = new NodeId("B");
        SystemStatus status = new SystemStatus("9", Arrays.asList(firstId, secondId));

        Status second = nodeStatus(secondId, TimingNodeTypes.State.OPEN);
        status.onTimingNodeStatus(second);

        SystemStatus.Snapshot partial = status.current();
        assertFalse(partial.timingNodeStatusComplete());
        assertEquals(1, partial.timingNodeStatuses().size());
        assertSame(second, partial.timingNodeStatuses().get(0));

        Status first = nodeStatus(firstId, TimingNodeTypes.State.CLOSED);
        status.onTimingNodeStatus(first);

        SystemStatus.Snapshot complete = status.current();
        assertTrue(complete.timingNodeStatusComplete());
        assertSame(first, complete.timingNodeStatuses().get(0));
        assertSame(second, complete.timingNodeStatuses().get(1));
    }

    @Test
    public void antennaContributionUsesSemanticDomainValues() {

        SystemStatus status = new SystemStatus(
                "9",
                Collections.singletonList(new NodeId("A")));
        SystemStatus.AntennaStatus antenna = new SystemStatus.AntennaStatus(
                "1",
                true,
                SystemStatus.AntennaOperation.INVENTORY,
                false,
                null);

        status.updateAntennaManager(
                SystemStatus.AntennaManagerState.ACTIVE,
                true,
                Collections.singletonList(antenna));

        SystemStatus.Snapshot snapshot = status.current();
        assertTrue(snapshot.hasAntennaManager());
        assertTrue(snapshot.antennaReady());
        assertEquals(SystemStatus.AntennaManagerState.ACTIVE, snapshot.antennaManagerState());
        assertSame(antenna, snapshot.antenna("1"));
        assertNull(snapshot.antenna("1").failureMessage());
    }

    @Test
    public void timingSystemLifecycleIsRepresentedSeparatelyFromIoReadiness() {

        SystemStatus status = new SystemStatus(
                "9",
                Collections.singletonList(new NodeId("A")));

        assertEquals(SystemStatus.State.NEW, status.current().state());
        assertFalse(status.current().hasAntennaManager());

        status.markActive();
        assertEquals(SystemStatus.State.ACTIVE, status.current().state());

        status.markInactive();
        assertEquals(SystemStatus.State.INACTIVE, status.current().state());
    }

    /** Creates one immutable TimingNode status for aggregation tests. */
    private static Status nodeStatus(NodeId nodeId, TimingNodeTypes.State state) {

        return new Status(nodeId, state, null, false);
    }
}
