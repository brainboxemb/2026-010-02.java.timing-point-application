package io.github.brainboxemb.eventtiming.timingpoint.testsupport;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookProcessorMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeTaskProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Test-only complete TimingNode construction with explicit execution lanes.
 *
 * <p>Production code receives these lanes from runtime composition. Presentation
 * and processing tests use this fixture so they do not duplicate runtime
 * executor wiring or fall back to hidden component-owned thread creation.</p>
 */
public final class TimingNodeFixture {
    private static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 5000L;
    /*
     * Test-suite-owned workers. They are daemon threads so a failed test cannot
     * keep the JVM alive; individual logical lanes still close with their node.
     */
    private static final ExecutorService NODE_WORKER =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(
                                runnable,
                                "test-node-worker");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static final ScheduledThreadPoolExecutor TAG_WORKER =
            new ScheduledThreadPoolExecutor(
                    1,
                    runnable -> {
                        Thread thread = new Thread(
                                runnable,
                                "test-tag-worker");
                        thread.setDaemon(true);
                        return thread;
                    });

    static {
        TAG_WORKER.setRemoveOnCancelPolicy(true);
    }

    private TimingNodeFixture() {
    }

    public static TimingNode create(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimeSource timeSource) {
        return create(
                nodeId,
                persistence,
                new DefaultTimingDataFactory(),
                timeSource);
    }

    public static TimingNode create(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource) {
        return create(
                nodeId,
                persistence,
                timingDataFactory,
                timeSource,
                EventData.empty(),
                UpstreamStream.unavailable());
    }

    public static TimingNode create(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            EventData eventData,
            UpstreamStream upstreamStream) {
        String suffix = nodeId == null ? "missing" : nodeId.value();
        return compose(
                nodeId,
                persistence,
                timingDataFactory,
                timeSource,
                eventData,
                upstreamStream,
                new SerialLane(
                        32,
                        "test-node-data-" + suffix,
                        NODE_WORKER),
                DEFAULT_OPERATION_TIMEOUT_MILLIS);
    }

    public static TimingNode createWithDataLane(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            SerialLane dataLane,
            long operationTimeoutMillis) {
        return compose(
                nodeId,
                persistence,
                timingDataFactory,
                timeSource,
                EventData.empty(),
                UpstreamStream.unavailable(),
                dataLane,
                operationTimeoutMillis);
    }

    private static TimingNode compose(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            EventData eventData,
            UpstreamStream upstreamStream,
            SerialLane dataLane,
            long operationTimeoutMillis) {
        String suffix = nodeId == null ? "missing" : nodeId.value();
        ReadOnlyConfiguration<TagProcessingPolicy> tagConfiguration =
                ReadOnlyConfiguration.fixed(
                        TagProcessingPolicy.defaults());

        LogBookProcessor logBookProcessor =
                new LogBookProcessor(
                        nodeId,
                        persistence,
                        timingDataFactory,
                        timeSource,
                        SystemMonotonicClock.INSTANCE,
                        new LogBookProcessorMetrics(),
                        dataLane,
                        operationTimeoutMillis);

        NodeTaskProcessor taskProcessor =
                new NodeTaskProcessor(
                        logBookProcessor,
                        new SerialLane(
                                32,
                                "test-node-task-" + suffix,
                                NODE_WORKER),
                        eventData,
                        upstreamStream);

        TagProcessor tagProcessor =
                new TagProcessor(
                        eventData,
                        tagConfiguration,
                        SystemMonotonicClock.INSTANCE,
                        new TagProcessingMetrics(),
                        new SerialLane(
                                32,
                                "test-tag-" + suffix,
                                TAG_WORKER),
                        TAG_WORKER);
        tagConfiguration.changes()
                .subscribe(
                        tagProcessor::onPolicyConfigurationChanged);

        return new TimingNode(
                logBookProcessor,
                taskProcessor,
                tagProcessor);
    }

}
