package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.RuntimeMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;

import java.util.ArrayList;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TimingNodeRuntimeMetricsTest {
    private static final TimingTimestamp EVENT_TIME =
            TimingTimestamp.parse("2026-10-04T12:00:00.000000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-04T12:00:01.000000000Z");

    @Test
    public void reportsQueuePersistenceCommitAndEventMetricsWithoutPerEventSamples() {
        RecordingPersistence persistence = new RecordingPersistence();
        TimeSource timeSource = () -> RECORDED_AT;
        TimingNode node = new TimingNode(
                new NodeId("TN-01"),
                persistence,
                new DefaultTimingDataFactory(),
                timeSource);
        node.timingDataCommittedEvent().subscribe(data -> {
            // One ordinary listener keeps event delivery in the measured path.
        });

        node.start();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));
            TimingNodeTypes.RegistrationResult result =
                    node.invoke(TimingNodeCommands.addAutomaticRegistration(
                            new RegistrationId("N0001"),
                            EVENT_TIME));
            assertTrue(result.committed());

            // invoke() returns when its Future completes. SerialExecutor updates its
            // completed counter immediately afterwards, so use one ordered query
            // as a barrier before sampling those asynchronous worker counters.
            assertEquals(
                    1,
                    node.query(TimingNodeQueries.timingDataCount()).intValue());

            RuntimeMetrics metrics = node.runtimeMetrics();
            assertTrue(metrics.queueAcceptedCount() >= 2L);
            assertEquals(0L, metrics.queueFullCount());
            assertEquals(0L, metrics.queueNotRunningCount());
            assertTrue(metrics.queueCompletedCount() >= 2L);
            assertTrue(metrics.queueHighWaterMark() >= 0);
            assertTrue(metrics.totalQueueWaitNanos() >= 0L);
            assertTrue(metrics.totalExecutionNanos() >= 0L);

            assertEquals(1L, metrics.timingDataAppendAttempts());
            assertEquals(0L, metrics.timingDataAppendFailures());
            assertEquals(1L, metrics.timingDataCommitCount());
            assertTrue(metrics.totalTimingDataAppendNanos() >= 0L);

            assertEquals(1L, metrics.timingDataEventDeliveries());
            assertEquals(0L, metrics.timingDataEventListenerFailures());
            assertTrue(metrics.totalTimingDataEventNanos() >= 0L);

            assertTrue(metrics.workerThreadCpuTimeNanos() >= -1L);
        } finally {
            node.stop();
        }
    }

    private static final class RecordingPersistence implements TimingDataPersistence {
        private final java.util.List<TimingData> records = new ArrayList<>();

        @Override
        public LoadResult load() {
            return new LoadResult(records, false);
        }

        @Override
        public void append(TimingData data) {
            records.add(data);
        }
    }
}
