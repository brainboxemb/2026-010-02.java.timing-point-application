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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamTaskState;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Test-only complete TimingNode construction with explicit execution lanes.
 *
 * <p>Production code receives these lanes from runtime composition. Tests that
 * intentionally verify child-specific behaviour can request {@link Components}
 * instead of widening the production TimingNode API.</p>
 */
public final class TimingNodeFixture {
    private static final long DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS =
            LogBook.DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS;
    private static final long DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS =
            LogBook.DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS;

    private static final ScheduledThreadPoolExecutor NODE_WORKER =
            new ScheduledThreadPoolExecutor(
                    1,
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
        NODE_WORKER.setRemoveOnCancelPolicy(true);
        TAG_WORKER.setRemoveOnCancelPolicy(true);
    }

    /** Prevents construction of this static test fixture. */
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
        private final UpstreamTaskState upstreamTaskState;

        /** Creates test-only references to one fully composed TimingNode graph. */
        private Components(
                TimingNode node,
                LogBook logBook,
                NodeService nodeService,
                TagProcessor tagProcessor,
                UpstreamHandler upstreamHandler,
                UpstreamTaskState upstreamTaskState) {
            this.node = node;
            this.logBook = logBook;
            this.nodeService = nodeService;
            this.tagProcessor = tagProcessor;
            this.upstreamHandler = upstreamHandler;
            this.upstreamTaskState = upstreamTaskState;
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

        /**
         * Returns the test-only passive upstream state view.
         *
         * <p>Use only after the direct fixture has completed the corresponding
         * Task Lane operation; production code never exposes this state.</p>
         */
        public UpstreamTaskState upstreamTaskState() {
            return upstreamTaskState;
        }
    }

    /** Creates one test TimingNode with default profile data and shared test workers. */
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

    /** Creates one test TimingNode with an explicit TimingDataFactory. */
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
                NODE_WORKER,
                32,
                32,
                DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS,
                DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS,
                new SerialTaskRunner(
                        32,
                        32,
                        "test-node-task-" + suffix,
                        NODE_WORKER),
                TAG_WORKER)
                .node();
    }

    /** Creates one same-thread TimingNode for deterministic child execution tests. */
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

    /** Creates one same-thread TimingNode with explicit EventData and upstream stream. */
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

    /** Creates same-thread TimingNode components for child-specific tests. */
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
                NODE_WORKER,
                32,
                32,
                DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS,
                DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS,
                new SerialTaskRunner(
                        32,
                        32,
                        "test-node-task-direct-" + suffix,
                        NODE_WORKER),
                TAG_WORKER);
    }

    /** Creates one TimingNode with explicit LogBook lane execution settings. */
    public static TimingNode createWithLogBookExecution(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            Executor dataLaneExecutor,
            int dataLaneCapacity,
            int dataLaneMaxItemsPerRun,
            long commitWaitTimeoutMillis,
            long orderedQueryWaitTimeoutMillis) {
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
                dataLaneExecutor,
                dataLaneCapacity,
                dataLaneMaxItemsPerRun,
                commitWaitTimeoutMillis,
                orderedQueryWaitTimeoutMillis,
                new SerialTaskRunner(
                        32,
                        32,
                        "test-node-task-direct-" + suffix,
                        NODE_WORKER),
                TAG_WORKER)
                .node();
    }

    /** Composes one complete test TimingNode graph from explicit logical lanes. */
    private static Components compose(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            EventData eventData,
            UpstreamStream upstreamStream,
            Executor dataLaneExecutor,
            int dataLaneCapacity,
            int dataLaneMaxItemsPerRun,
            long commitWaitTimeoutMillis,
            long orderedQueryWaitTimeoutMillis,
            SerialTaskRunner taskLane,
            ScheduledThreadPoolExecutor tagWorker) {
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
                        dataLaneExecutor,
                        dataLaneCapacity,
                        dataLaneMaxItemsPerRun,
                        commitWaitTimeoutMillis,
                        orderedQueryWaitTimeoutMillis);

        NodeServiceState nodeServiceState = new NodeServiceState(eventData);

        NodeService nodeService = new NodeService(nodeId, taskLane, nodeServiceState);

        UpstreamHandler upstreamHandler =
                new UpstreamHandler(
                        nodeId,
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
                        tagWorker);

        TimingNode node =
                new TimingNode(
                        new AntennaId("1"),
                        timeSource,
                        logBook,
                        nodeService,
                        tagProcessor,
                        taskLane);

        return new Components(
                node,
                logBook,
                nodeService,
                tagProcessor,
                upstreamHandler,
                nodeServiceState);
    }
}
