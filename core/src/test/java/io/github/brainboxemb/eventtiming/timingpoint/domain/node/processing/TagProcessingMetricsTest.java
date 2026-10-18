package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TagProcessingMetricsTest {

    @Test
    public void snapshotContainsProcessingAndPassagePoolMetrics() {
        TagProcessingMetrics metrics = new TagProcessingMetrics();

        metrics.recordObservation();
        metrics.recordObservation();
        metrics.recordObservationQueueFull();
        metrics.recordProcessorNotRunning();
        metrics.recordClosedBurst();
        metrics.recordMapped();
        metrics.recordUnmapped();
        metrics.recordDuplicate();
        metrics.recordPassagePool(
                8,
                10,
                3,
                6);

        TagProcessingMetrics.Snapshot snapshot = metrics.snapshot();

        assertEquals(2L, snapshot.observations());
        assertEquals(1L, snapshot.observationQueueFull());
        assertEquals(1L, snapshot.processorNotRunning());
        assertEquals(1L, snapshot.closedBursts());
        assertEquals(1L, snapshot.mapped());
        assertEquals(1L, snapshot.unmapped());
        assertEquals(1L, snapshot.duplicates());
        assertEquals(8, snapshot.passagePoolInitialSize());
        assertEquals(10, snapshot.passagePoolSize());
        assertEquals(3, snapshot.passagePoolInUse());
        assertEquals(6, snapshot.passagePoolPeakInUse());
    }
}
