package io.github.brainboxemb.eventtiming.timingpoint.testsupport;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeServiceState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Test-only complete TimingNode construction with explicit execution lanes.
 *
 * <p>Production code receives these lanes from runtime composition. Tests that
 * intentionally verify child-specific behaviour can request {@link Components}
 * instead of widening the production TimingNode API.</p>
 */
public final class TimingNodeFixture {
    private static final long DEFAULT_OPERATION_TIMEOUT_MILLIS = 5000L;

    private static final ExecutorService NODE_WORKER =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "test-node-worker");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static final ScheduledThreadPoolExecutor TAG_WORKER =
            new ScheduledThreadPoolExecutor(
                    1,
                    runnable -> {
                        Thread thread =
                                new Thread(
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

    /**
     * Test-only references to one fully composed node and its owned children.
     */
    public static final class Components {
        private final TimingNode node;
        private final LogBook logBook;
        private final NodeService nodeService;
        private final TagProcessor tagProcessor;
        private final UpstreamHandler upstreamHandler;

        /** Creates test-only references to one fully composed TimingNode graph. */
        private Components(
                TimingNode node,
                LogBook logBook,
                NodeService nodeService,
                TagProcessor tagProcessor,
                UpstreamHandler upstreamHandler) {
            this.node = node;
            this.logBook = logBook;
            this.nodeService = nodeService;
            this.tagProcessor = tagProcessor;
            this.upstreamHandler = upstreamHandler;
        }

        /** Returns the composed TimingNode owner. */
        public TimingNode node() {
            return node;
        }

        /** Returns the concrete LogBook for child-specific tests. */
        public LogBook logBook() {
            return logBook;
        }

        /** Returns the NodeService for child-specific tests. */
        public NodeService nodeService() {
            return nodeService;
        }

        /** Returns the TagProcessor for child-specific tests. */
        public TagProcessor tagProcessor() {
            return tagProcessor;
        }

        /** Returns the separately composed upstream protocol boundary. */
        public UpstreamHandler upstreamHandler() {
            return upstreamHandler;
        }
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
        String suffix =
                nodeId == null
                        ? "missing"
                        : nodeId.value();

        return compose(
                nodeId,
                persistence,
                timingDataFactory,
                timeSource,
                EventData.empty(),
                UpstreamStream.unavailable(),
                new SerialLane(
                        32,
                        "test-node-data-" + suffix,
                        NODE_WORKER),
                new SerialLane(
                        32,
                        "test-node-task-" + suffix,
                        NODE_WORKER),
                new SerialLane(
                        32,
                        "test-tag-" + suffix,
                        TAG_WORKER),
                DEFAULT_OPERATION_TIMEOUT_MILLIS)
                .node();
    }

    public static TimingNode createDirect(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource) {
        return createDirect(
                nodeId,
                persistence,
                timingDataFactory,
                timeSource,
                EventData.empty(),
                UpstreamStream.unavailable());
    }

    public static TimingNode createDirect(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            EventData eventData,
            UpstreamStream upstreamStream) {
        return createDirectComponents(
                nodeId,
                persistence,
                timingDataFactory,
                timeSource,
                eventData,
                upstreamStream)
                .node();
    }

    public static Components createDirectComponents(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            EventData eventData,
            UpstreamStream upstreamStream) {
        String suffix =
                nodeId == null
                        ? "missing"
                        : nodeId.value();

        return compose(
                nodeId,
                persistence,
                timingDataFactory,
                timeSource,
                eventData,
                upstreamStream,
                new SerialLane(
                        32,
                        "test-node-data-direct-" + suffix,
                        Runnable::run),
                new SerialLane(
                        32,
                        "test-node-task-direct-" + suffix,
                        Runnable::run),
                new SerialLane(
                        32,
                        "test-tag-direct-" + suffix,
                        Runnable::run),
                DEFAULT_OPERATION_TIMEOUT_MILLIS);
    }

    public static TimingNode createWithDataLane(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            SerialLane dataLane,
            long operationTimeoutMillis) {
        String suffix =
                nodeId == null
                        ? "missing"
                        : nodeId.value();

        return compose(
                nodeId,
                persistence,
                timingDataFactory,
                timeSource,
                EventData.empty(),
                UpstreamStream.unavailable(),
                dataLane,
                new SerialLane(
                        32,
                        "test-node-task-direct-" + suffix,
                        Runnable::run),
                new SerialLane(
                        32,
                        "test-tag-direct-" + suffix,
                        Runnable::run),
                operationTimeoutMillis)
                .node();
    }

    private static Components compose(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            EventData eventData,
            UpstreamStream upstreamStream,
            SerialLane dataLane,
            SerialLane taskLane,
            SerialLane tagLane,
            long operationTimeoutMillis) {
        ReadOnlyConfiguration<TagProcessingPolicy> tagConfiguration =
                ReadOnlyConfiguration.fixed(
                        TagProcessingPolicy.defaults());

        LogBook logBook =
                new LogBook(
                        nodeId,
                        persistence,
                        timingDataFactory,
                        timeSource,
                        SystemMonotonicClock.INSTANCE,
                        new LogBookMetrics(),
                        dataLane,
                        operationTimeoutMillis);

        NodeServiceState nodeServiceState =
                new NodeServiceState(
                        eventData);

        NodeService nodeService =
                new NodeService(
                        logBook,
                        taskLane,
                        nodeServiceState);

        UpstreamHandler upstreamHandler =
                new UpstreamHandler(
                        nodeId,
                        dataLane,
                        taskLane,
                        upstreamStream,
                        logBook,
                        nodeServiceState);

        TagProcessor tagProcessor =
                new TagProcessor(
                        eventData,
                        tagConfiguration,
                        SystemMonotonicClock.INSTANCE,
                        new TagProcessingMetrics(),
                        tagLane,
                        TAG_WORKER);

        tagConfiguration.changes()
                .subscribe(
                        tagProcessor::onPolicyConfigurationChanged);

        TimingNode node =
                new TimingNode(
                        logBook,
                        nodeService,
                        tagProcessor);

        return new Components(
                node,
                logBook,
                nodeService,
                tagProcessor,
                upstreamHandler);
    }
}
