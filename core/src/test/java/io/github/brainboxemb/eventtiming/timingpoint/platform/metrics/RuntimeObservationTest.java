package io.github.brainboxemb.eventtiming.timingpoint.platform.metrics;

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
        assertTrue(
                RuntimeObservation.threadCpuTimeNanos(
                        "tp-dml-",
                        "tp-apl-",
                        "tp-io-")
                        >= -1L);
    }
}
