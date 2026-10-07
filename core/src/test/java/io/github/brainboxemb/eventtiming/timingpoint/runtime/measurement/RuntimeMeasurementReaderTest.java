package io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuntimeMeasurementReaderTest {
    private static final TimingTimestamp EVENT_TIME =
            TimingTimestamp.parse(
                    "2026-10-04T12:00:00.000000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse(
                    "2026-10-04T12:00:01.000000000Z");

    @Test
    public void readsComponentOwnedSnapshotsWithoutTimingNodeMetricsApi()
            throws Exception {
        ExecutorService nodeWorker =
                Executors.newSingleThreadExecutor();
        ScheduledThreadPoolExecutor tagWorker =
                new ScheduledThreadPoolExecutor(
                        1);
        tagWorker.setRemoveOnCancelPolicy(
                true);

        SerialExecutor nodeLane =
                new SerialExecutor(
                        8,
                        "measurement-node",
                        nodeWorker);
        SerialScheduledExecutor tagLane =
                new SerialScheduledExecutor(
                        8,
                        "measurement-tag",
                        tagWorker);
        TimingNodeMetrics nodeMetrics =
                new TimingNodeMetrics();
        TagProcessingMetrics tagMetrics =
                new TagProcessingMetrics();
        RecordingPersistence persistence =
                new RecordingPersistence();
        TimeSource timeSource =
                () -> RECORDED_AT.instant();

        TimingNode node =
                new TimingNode(
                        new NodeId("A"),
                        persistence,
                        new DefaultTimingDataFactory(),
                        timeSource,
                        ReadOnlyConfiguration.fixed(
                                TagProcessingPolicy.defaults()),
                        EventData.empty(),
                        nodeLane,
                        tagLane,
                        SystemMonotonicClock.INSTANCE,
                        nodeMetrics,
                        tagMetrics);

        RuntimeMeasurementReader reader =
                new RuntimeMeasurementReader(
                        nodeLane.metrics(),
                        nodeMetrics,
                        tagMetrics);

        node.timingDataCommittedEvent()
                .subscribe(
                        ignored -> {
                            // Keep one ordinary listener in the measured path.
                        });

        node.activate();
        try {
            node.invoke(
                    TimingNodeCommands.open(
                            new LocationId(24)));
            assertTrue(
                    node.invoke(
                                    TimingNodeCommands.addAutomaticRegistration(
                                            new RegistrationId("N0001"),
                                            EVENT_TIME))
                            .committed());

            /*
             * Ordered query is a barrier for the lane completion counter before
             * the pull snapshot is created.
             */
            assertEquals(
                    2,
                    node.query(
                                    TimingNodeQueries.timingDataCount())
                            .intValue());

            TimingNodeRuntimeSnapshot nodeSnapshot =
                    reader.timingNode();
            assertTrue(
                    nodeSnapshot.queueAcceptedCount() >= 2L);
            assertEquals(
                    0L,
                    nodeSnapshot.queueFullCount());
            assertEquals(
                    2L,
                    nodeSnapshot.timingDataAppendAttempts());
            assertEquals(
                    0L,
                    nodeSnapshot.timingDataAppendFailures());
            assertEquals(
                    2L,
                    nodeSnapshot.timingDataCommitCount());
            assertEquals(
                    2L,
                    nodeSnapshot.timingDataEventDeliveries());

            TagProcessingMetrics.Snapshot tagSnapshot =
                    reader.tagProcessing();
            assertEquals(
                    0L,
                    tagSnapshot.observations());

            JvmRuntimeSnapshot jvm =
                    reader.jvm();
            assertTrue(
                    jvm.heapUsedBytes() >= 0L);
            assertTrue(
                    jvm.liveThreadCount() > 0);
            assertTrue(
                    jvm.roleWorkerCpuTimeNanos() >= -1L);
        } finally {
            node.deactivate();
            tagWorker.shutdownNow();
            nodeWorker.shutdownNow();
        }
    }

    private static final class RecordingPersistence
            implements TimingDataPersistence {
        private final java.util.List<TimingData> records =
                new ArrayList<TimingData>();

        @Override
        public LoadResult load() {
            return new LoadResult(
                    records,
                    false);
        }

        @Override
        public void append(
                TimingData data) {
            records.add(
                    data);
        }
    }
}
