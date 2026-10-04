package io.github.brainboxemb.eventtiming.timingpoint.platform.environment;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class RuntimeObservationTest {
    @Test
    public void capturesJdkRuntimeObservationsOnDemand() {
        RuntimeObservation.Snapshot snapshot = RuntimeObservation.capture();

        assertTrue(snapshot.heapUsedBytes() >= 0L);
        assertTrue(snapshot.liveThreadCount() > 0);
        assertTrue(snapshot.gcCollectionCount() >= -1L);
        assertTrue(snapshot.gcCollectionTimeMillis() >= -1L);
    }
}
