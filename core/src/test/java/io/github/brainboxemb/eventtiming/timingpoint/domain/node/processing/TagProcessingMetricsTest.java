package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TagProcessingMetricsTest {

    @Test
    public void snapshotContainsProcessingAndPrimitiveStateMetrics() {
        TagProcessingMetrics metrics = new TagProcessingMetrics();

        metrics.recordObservation();
        metrics.recordObservation();
        metrics.recordObservationQueueFull();
        metrics.recordProcessorNotRunning();
        metrics.recordClosedPassage();
        metrics.recordMapped();
        metrics.recordUnmapped();
        metrics.recordDuplicate();
        metrics.recordPassagePool(
                8,
                10,
                3,
                6);
        metrics.recordDuplicateTable(
                16,
                32,
                7,
                9);

        TagProcessingMetrics.Snapshot snapshot = metrics.snapshot();

        assertEquals(2L, snapshot.observations());
        assertEquals(1L, snapshot.observationQueueFull());
        assertEquals(1L, snapshot.processorNotRunning());
        assertEquals(1L, snapshot.closedPassages());
        assertEquals(1L, snapshot.mapped());
        assertEquals(1L, snapshot.unmapped());
        assertEquals(1L, snapshot.duplicates());
        assertEquals(8, snapshot.passagePoolInitialSize());
        assertEquals(10, snapshot.passagePoolSize());
        assertEquals(3, snapshot.passagePoolInUse());
        assertEquals(6, snapshot.passagePoolPeakInUse());
        assertEquals(16, snapshot.duplicateTableInitialCapacity());
        assertEquals(32, snapshot.duplicateTableCapacity());
        assertEquals(7, snapshot.duplicateTableSize());
        assertEquals(9, snapshot.duplicateTablePeakSize());
    }
}
