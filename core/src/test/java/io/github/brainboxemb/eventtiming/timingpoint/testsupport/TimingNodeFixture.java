package io.github.brainboxemb.eventtiming.timingpoint.testsupport;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.NodeUpstream;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamConnector;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGateway;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodec;

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
        private final NodeUpstream nodeUpstream;
        private final RaceDataState raceData;

        /** Creates test-only references to one fully composed TimingNode graph. */
        private Components(
                TimingNode node,
                LogBook logBook,
                NodeService nodeService,
                TagProcessor tagProcessor,
                NodeUpstream nodeUpstream,
                RaceDataState raceData) {
            this.node = node;
            this.logBook = logBook;
            this.nodeService = nodeService;
            this.tagProcessor = tagProcessor;
            this.nodeUpstream = nodeUpstream;
            this.raceData = raceData;
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
        public NodeUpstream nodeUpstream() {
            return nodeUpstream;
        }

        /** Returns the test-owned RaceDataState used by NodeService. */
        public RaceDataState raceData() {
            return raceData;
        }
    }

    /** Creates one test TimingNode with empty EventData and shared test workers. */
    public static TimingNode create(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimeSource timeSource) {
        return create(
                nodeId,
                persistence,
                timeSource,
                EventData.empty());
    }

    /** Creates one test TimingNode with explicit EventData and shared test workers. */
    public static TimingNode create(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimeSource timeSource,
            EventData eventData) {
        String suffix =
                nodeId == null
                        ? "missing"
                        : nodeId.value();

        return compose(
                nodeId,
                persistence,
                timeSource,
                eventData,
                unavailableStream(nodeId),
                NODE_WORKER,
                32,
                32,
                DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS,
                DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS,
                NODE_WORKER,
                TAG_WORKER)
                .node();
    }

    /** Creates one same-thread TimingNode for deterministic child execution tests. */
    public static TimingNode createDirect(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimeSource timeSource) {
        return createDirect(
                nodeId,
                persistence,
                timeSource,
                EventData.empty(),
                unavailableStream(nodeId));
    }

    /** Creates one same-thread TimingNode with explicit EventData and upstream stream. */
    public static TimingNode createDirect(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimeSource timeSource,
            EventData eventData,
            UpstreamStream upstreamStream) {
        return createDirectComponents(
                nodeId,
                persistence,
                timeSource,
                eventData,
                upstreamStream)
                .node();
    }

    /** Creates same-thread TimingNode components for child-specific tests. */
    public static Components createDirectComponents(
            NodeId nodeId,
            TimingDataPersistence persistence,
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
                timeSource,
                eventData,
                upstreamStream,
                NODE_WORKER,
                32,
                32,
                DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS,
                DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS,
                NODE_WORKER,
                TAG_WORKER);
    }

    /** Creates one TimingNode with explicit LogBook lane execution settings. */
    public static TimingNode createWithLogBookExecution(
            NodeId nodeId,
            TimingDataPersistence persistence,
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
                timeSource,
                EventData.empty(),
                unavailableStream(nodeId),
                dataLaneExecutor,
                dataLaneCapacity,
                dataLaneMaxItemsPerRun,
                commitWaitTimeoutMillis,
                orderedQueryWaitTimeoutMillis,
                NODE_WORKER,
                TAG_WORKER)
                .node();
    }

    /** Composes one complete test TimingNode graph from explicit logical lanes. */
    private static Components compose(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimeSource timeSource,
            EventData eventData,
            UpstreamStream upstreamStream,
            Executor dataLaneExecutor,
            int dataLaneCapacity,
            int dataLaneMaxItemsPerRun,
            long commitWaitTimeoutMillis,
            long orderedQueryWaitTimeoutMillis,
            ScheduledThreadPoolExecutor nodeWorker,
            ScheduledThreadPoolExecutor tagWorker) {
        ReadOnlyConfiguration<TagProcessingPolicy> tagConfiguration =
                ReadOnlyConfiguration.fixed(
                        TagProcessingPolicy.defaults());

        LogBook logBook =
                new LogBook(
                        nodeId,
                        persistence,
                        timeSource,
                        SystemMonotonicClock.INSTANCE,
                        new LogBookMetrics(),
                        dataLaneExecutor,
                        dataLaneCapacity,
                        dataLaneMaxItemsPerRun,
                        commitWaitTimeoutMillis,
                        orderedQueryWaitTimeoutMillis);

        RaceDataState raceData = new RaceDataState(eventData);
        ActiveRegistrations activeRegistrations = new ActiveRegistrations();

        NodeService nodeService =
                new NodeService(
                        nodeId,
                        nodeWorker,
                        logBook,
                        timeSource,
                        eventData,
                        raceData,
                        activeRegistrations);

        NodeUpstream nodeUpstream =
                new NodeUpstream(
                        nodeId,
                        nodeWorker,
                        upstreamStream,
                        new DefaultUpstreamDataCodec(
                                new DefaultTimingDataCodec()),
                        logBook,
                        nodeService);

        NodeUpstream.CONNECTED.bind(nodeUpstream, upstreamStream.CONNECTED);
        NodeUpstream.DATA_AVAILABLE.bind(nodeUpstream, upstreamStream.DATA_AVAILABLE);

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
                        nodeWorker);

        return new Components(
                node,
                logBook,
                nodeService,
                tagProcessor,
                nodeUpstream,
                raceData);
    }
    /** Creates a real application port over a permanently unavailable transport double. */
    private static UpstreamStream unavailableStream(NodeId nodeId) {
        UpstreamGateway gateway = new UpstreamGateway();
        return gateway.createApplicationPort(nodeId, new UnavailableConnector());
    }

    /** Minimal unavailable connector used when a test does not exercise upstream transport. */
    private static final class UnavailableConnector extends UpstreamConnector {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void send(NodeId nodeId, String encodedMessage) {
            throw new IllegalStateException("test upstream communication is unavailable");
        }
    }

}
