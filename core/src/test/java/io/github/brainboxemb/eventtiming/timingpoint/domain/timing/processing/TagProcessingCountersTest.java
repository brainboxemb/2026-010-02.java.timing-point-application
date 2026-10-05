package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TagProcessingCountersTest {

    @Test
    public void snapshotContainsCumulativeProcessingCounters() {
        TagProcessingCounters counters = new TagProcessingCounters();

        counters.recordObservation();
        counters.recordObservation();
        counters.recordObservationQueueFull();
        counters.recordProcessorNotRunning();
        counters.recordClosedBurst();
        counters.recordMapped();
        counters.recordUnmapped();
        counters.recordDuplicate();
        counters.recordAdmission(CommandAdmission.ACCEPTED);
        counters.recordAdmission(CommandAdmission.FULL);
        counters.recordAdmission(CommandAdmission.NOT_RUNNING);

        TagProcessingCounters.Snapshot snapshot = counters.snapshot();

        assertEquals(2L, snapshot.observations());
        assertEquals(1L, snapshot.observationQueueFull());
        assertEquals(1L, snapshot.processorNotRunning());
        assertEquals(1L, snapshot.closedBursts());
        assertEquals(1L, snapshot.mapped());
        assertEquals(1L, snapshot.unmapped());
        assertEquals(1L, snapshot.duplicates());
        assertEquals(1L, snapshot.admitted());
        assertEquals(1L, snapshot.queueFull());
        assertEquals(1L, snapshot.nodeNotRunning());
    }
}
