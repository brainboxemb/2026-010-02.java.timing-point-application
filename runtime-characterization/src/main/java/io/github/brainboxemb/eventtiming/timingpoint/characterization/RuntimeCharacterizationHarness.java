package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataProvider;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeDataLane;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeServiceState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.RunnableSerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.ScheduledSerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.TaskSerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.ClockTimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.JvmRuntimeSnapshot;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.RuntimeMeasurementReader;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.TimingNodeRuntimeSnapshot;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Direct engineering composition for repeatable runtime characterization.
 *
 * <p>The measured input follows the same accepted product path:
 * SimulatedAntenna -> TagProcessor -> TimingNode -> persistence/LogBook.</p>
 */
final class RuntimeCharacterizationHarness
        implements AutoCloseable {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final LocationId LOCATION_ID = new LocationId(1);
    private static final AntennaId ANTENNA_ID = new AntennaId("1");
    private static final int TIMING_NODE_QUEUE_CAPACITY = 32;
    private static final int TAG_PROCESSOR_QUEUE_CAPACITY = 32;
    private static final int ANTENNA_CONTROL_QUEUE_CAPACITY = 8;
    private static final Duration ANTENNA_CONTROL_TIMEOUT = Duration.ofSeconds(2);
    private static final long COMPLETION_TIMEOUT_MILLIS = 5000L;
    private static final long INITIAL_LIFECYCLE_RECORD_COUNT = 1L;
    private static final Instant INPUT_TIME_BASE =
            Instant.parse("2026-01-01T00:00:00Z");

    static int timingNodeQueueCapacity() {
        return TIMING_NODE_QUEUE_CAPACITY;
    }

    static int tagProcessorQueueCapacity() {
        return TAG_PROCESSOR_QUEUE_CAPACITY;
    }

    private final CharacterizationConfig config;
    private final AtomicLong committedCount = new AtomicLong();

    private ScheduledThreadPoolExecutor nodeWorker;
    private ScheduledThreadPoolExecutor tagWorker;
    private ScheduledThreadPoolExecutor ioWorker;
    private TimingNodeDataLane dataLane;
    private TaskSerialLane taskLane;
    private ScheduledSerialLane tagLane;
    private TaskSerialLane antennaLane;
    private SimulatedAntenna antenna;
    private AntennaManager antennaManager;
    private LogBook logBook;
    private NodeService nodeService;
    private TagProcessor tagProcessor;
    private TimingNode node;
    private RuntimeMeasurementReader measurements;
    private final ComponentLifecycleManager nodeLifecycle =
            new ComponentLifecycleManager();
    private final ComponentLifecycleManager antennaLifecycle =
            new ComponentLifecycleManager();

    private boolean composed;
    private boolean nodeActive;
    private boolean antennaManagerActive;

    RuntimeCharacterizationHarness(
            CharacterizationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null");
        }
        this.config = config;
    }

    /**
     * Composes the engineering runtime explicitly.
     *
     * <p>The constructor deliberately performs no filesystem, executor or
     * component work. Composition starts only when {@link #run()} is invoked.</p>
     */
    private void compose()
            throws IOException {
        if (composed) {
            throw new IllegalStateException(
                    "characterization harness is already composed");
        }

        prepareWorkDirectory(
                config.workDirectory());

        nodeWorker =
                new ScheduledThreadPoolExecutor(
                        1,
                        threadFactory(
                                "tp-dml-node-worker"));
        nodeWorker.setRemoveOnCancelPolicy(
                true);
        tagWorker =
                new ScheduledThreadPoolExecutor(
                        1,
                        threadFactory(
                                "tp-dml-tagproc-worker"));
        tagWorker.setRemoveOnCancelPolicy(
                true);
        ioWorker =
                new ScheduledThreadPoolExecutor(
                        1,
                        threadFactory(
                                "tp-io-shared-worker"));
        ioWorker.setRemoveOnCancelPolicy(
                true);

        dataLane =
                new TimingNodeDataLane(
                        TIMING_NODE_QUEUE_CAPACITY,
                        TIMING_NODE_QUEUE_CAPACITY,
                        "TimingNodeData",
                        nodeWorker);
        taskLane =
                new TaskSerialLane(
                        TIMING_NODE_QUEUE_CAPACITY,
                        TIMING_NODE_QUEUE_CAPACITY,
                        "TimingNodeTask",
                        nodeWorker);
        tagLane =
                new ScheduledSerialLane(
                        TAG_PROCESSOR_QUEUE_CAPACITY,
                        TAG_PROCESSOR_QUEUE_CAPACITY,
                        "TagProcessor",
                        tagWorker);
        antennaLane =
                new TaskSerialLane(
                        ANTENNA_CONTROL_QUEUE_CAPACITY,
                        ANTENNA_CONTROL_QUEUE_CAPACITY,
                        "AntennaManager",
                        ioWorker,
                        ANTENNA_CONTROL_TIMEOUT);

        LogBookMetrics logBookMetrics =
                new LogBookMetrics();
        TagProcessingMetrics tagMetrics =
                new TagProcessingMetrics();

        TimingDataProvider timingDataProvider =
                new DefaultTimingDataProvider();

        EventData nodeEventData =
                eventData(
                        config.warmupCount()
                                + config.measuredCount());
        ReadOnlyConfiguration<TagProcessingPolicy> tagConfiguration =
                ReadOnlyConfiguration.fixed(
                        TagProcessingPolicy.defaults());

        ClockTimeSource timeSource =
                new ClockTimeSource(
                        Clock.systemUTC());

        logBook =
                new LogBook(
                        NODE_ID,
                        new DefaultTimingDataPersistence(
                                new FileAppendOnlyRecordStore(
                                        config.workDirectory()
                                                .resolve(
                                                        "timing-data.jsonl")),
                                NODE_ID,
                                timingDataProvider.createCodec()),
                        timingDataProvider.createFactory(),
                        timeSource,
                        SystemMonotonicClock.INSTANCE,
                        logBookMetrics,
                        dataLane,
                        LogBook.DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS,
                        LogBook.DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS);

        NodeServiceState nodeServiceState =
                new NodeServiceState(
                        nodeEventData);
        nodeService =
                new NodeService(
                        NODE_ID,
                        taskLane,
                        nodeServiceState);

        tagProcessor =
                new TagProcessor(
                        nodeEventData,
                        tagConfiguration,
                        SystemMonotonicClock.INSTANCE,
                        tagMetrics,
                        tagLane);
        node =
                new TimingNode(
                        ANTENNA_ID,
                        timeSource,
                        logBook,
                        nodeService,
                        tagProcessor);

        antenna =
                new SimulatedAntenna();
        AntennaSet antennaSet =
                new AntennaSet()
                        .add(
                                ANTENNA_ID,
                                antenna);
        antennaManager =
                new AntennaManager(
                        antennaSet,
                        antennaLane);

        TimingNode.TAG_OBSERVATIONS.bind(
                node,
                antennaManager.tagObservedEvent(
                        ANTENNA_ID));
        nodeLifecycle.register(node);
        antennaLifecycle.register(antennaManager);
        logBook.newDataEntryEvent()
                .subscribe(
                        ignored ->
                                committedCount.incrementAndGet());

        measurements =
                new RuntimeMeasurementReader(
                        dataLane.metrics(),
                        logBookMetrics,
                        tagMetrics);

        enableThreadCpuTimeWhenSupported();
        composed = true;
    }

    Result run()
            throws Exception {
        compose();

        Instant startedAt =
                Instant.now();

        nodeLifecycle.activateAll();
        nodeActive = true;
        await(
                () -> node.operationalState()
                        == io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationalState.OK,
                "TimingNode operational");
        node.open(LOCATION_ID);

        preloadHistory(
                config.preloadCount());

        antennaLifecycle.activateAll();
        antennaManagerActive = true;
        await(
                antennaManager::isReady,
                "AntennaManager ready");
        if (!antennaManager.requestEnableInventory()) {
            throw new IllegalStateException(
                    "AntennaManager rejected characterization inventory enable");
        }
        await(
                antenna::inventoryRunning,
                "simulated antenna inventory");

        boolean burstWorkload =
                config.workload()
                        == CharacterizationConfig.Workload.BURST;

        runInput(
                0,
                config.warmupCount(),
                !burstWorkload);
        if (burstWorkload) {
            awaitBurstSettled(
                    config.preloadCount(),
                    config.warmupCount());
        } else {
            awaitCommitted(
                    INITIAL_LIFECYCLE_RECORD_COUNT
                            + config.preloadCount()
                            + config.warmupCount());
        }

        int historyAtMeasurementStart =
                logBook.process(
                        LogBookQueries.timingDataCount());

        TimingNodeRuntimeSnapshot nodeBefore =
                measurements.timingNode();
        TagProcessingMetrics.Snapshot tagBefore =
                measurements.tagProcessing();
        JvmRuntimeSnapshot jvmBefore =
                measurements.jvm();

        long measuredStartedNanos =
                System.nanoTime();
        runInput(
                config.warmupCount(),
                config.measuredCount(),
                !burstWorkload);
        if (burstWorkload) {
            awaitBurstSettled(
                    config.preloadCount(),
                    config.warmupCount()
                            + config.measuredCount());
        } else {
            awaitCommitted(
                    INITIAL_LIFECYCLE_RECORD_COUNT
                            + config.preloadCount()
                            + config.warmupCount()
                            + config.measuredCount());
        }
        long measuredElapsedNanos =
                Math.max(
                        0L,
                        System.nanoTime()
                                - measuredStartedNanos);

        TimingNodeRuntimeSnapshot nodeAfter =
                measurements.timingNode();
        TagProcessingMetrics.Snapshot tagAfter =
                measurements.tagProcessing();
        JvmRuntimeSnapshot jvmAfter =
                measurements.jvm();

        QueryMeasurement query =
                measureHistoryQuery();

        return new Result(
                startedAt,
                Instant.now(),
                historyAtMeasurementStart,
                measuredElapsedNanos,
                nodeBefore,
                nodeAfter,
                tagBefore,
                tagAfter,
                jvmBefore,
                jvmAfter,
                query);
    }

    private void preloadHistory(
            int count) {
        for (int index = 0; index < count; index++) {
            TimingTimestamp time =
                    new TimingTimestamp(
                            INPUT_TIME_BASE.minusSeconds(
                                    count - index));
            logBook.process(
                    LogBookCommands.commitAntennaRegistration(
                            LOCATION_ID,
                            new RegistrationId(
                                    String.format(
                                            "P%05d",
                                            index + 1)),
                            time));
        }
    }

    private void runInput(
            int startIndex,
            int count,
            boolean paced)
            throws InterruptedException {
        long intervalNanos =
                TimeUnit.SECONDS.toNanos(1L)
                        / config.registrationsPerSecond();
        long started =
                System.nanoTime();

        for (int offset = 0; offset < count; offset++) {
            int inputIndex =
                    startIndex + offset;

            if (paced && offset > 0) {
                sleepUntil(
                        started
                                + intervalNanos * offset);
            }

            antenna.emit(
                    new TagId(
                            tagId(
                                    inputIndex)),
                    -50,
                    new TimingTimestamp(
                            INPUT_TIME_BASE.plusNanos(
                                    intervalNanos * inputIndex)));
        }
    }

    private QueryMeasurement measureHistoryQuery() {
        final AtomicLong visited =
                new AtomicLong();

        long started =
                System.nanoTime();
        int total =
                logBook.process(
                        LogBookQueries.visitLatestTimingData(
                                config.queryLimit(),
                                ignored ->
                                        visited.incrementAndGet()));
        long elapsed =
                Math.max(
                        0L,
                        System.nanoTime()
                                - started);

        return new QueryMeasurement(
                config.queryLimit(),
                visited.get(),
                total,
                elapsed);
    }

    /**
     * Waits until an unpaced burst has reached a stable bounded outcome.
     *
     * <p>A characterization burst is allowed to expose bounded queue rejection.
     * The harness therefore must not assume every observation becomes committed
     * TimingData. It first waits until every emitted observation was either
     * processed or rejected at TagProcessor ingress, then until no active
     * passage remains. Finally it drains TagProcessor and then the Data Lane,
     * so every registration event already handed to TimingNode has completed.</p>
     */
    private void awaitBurstSettled(
            long preloadedCount,
            long expectedObservationCount)
            throws InterruptedException {
        await(
                () -> {
                    TagProcessingMetrics.Snapshot snapshot =
                            measurements.tagProcessing();

                    long processedOrRejected =
                            snapshot.mapped()
                                    + snapshot.unmapped()
                                    + snapshot.observationQueueFull()
                                    + snapshot.processorNotRunning();

                    return snapshot.observations()
                                    >= expectedObservationCount
                            && processedOrRejected
                                    >= expectedObservationCount
                            && tagProcessor
                                    .passageSnapshots()
                                    .isEmpty();
                },
                "burst processing to settle");

        /*
         * Metrics and filter snapshots are observations, not lane barriers.
         * Drain TagProcessor first and then the TimingNode Data Lane. This keeps
         * the subsequent ORDERED history query out of the burst tail.
         */
        awaitTagProcessorBarrier();
        awaitDataLaneBarrier();
    }

    private void awaitCommitted(
            long expected)
            throws InterruptedException {
        await(
                () -> committedCount.get() >= expected,
                "TimingData commit count " + expected);
    }

    /** Waits until all TagProcessor work queued before this barrier has completed. */
    private void awaitTagProcessorBarrier()
            throws InterruptedException {
        awaitLaneBarrier(
                tagLane,
                "TagProcessor");
    }

    /**
     * Waits until one ordered LogBook query can enter the Data Lane and complete.
     *
     * <p>A burst may temporarily fill the bounded item queue. FULL therefore
     * means the barrier itself must retry later; other rejection/failure
     * reasons remain real characterization failures.</p>
     */
    private void awaitDataLaneBarrier()
            throws InterruptedException {

        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(
                                COMPLETION_TIMEOUT_MILLIS);

        while (true) {
            try {
                logBook.process(
                        LogBookQueries.timingDataCount());
                return;
            } catch (IllegalStateException ex) {
                boolean full =
                        ex.getCause()
                                        instanceof RejectedExecutionException
                                && ex.getMessage() != null
                                && ex.getMessage().contains(
                                        "Data Lane is full");
                if (!full) {
                    throw ex;
                }
            }

            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "Timed out waiting for TimingNode Data Lane barrier admission");
            }
            Thread.sleep(5L);
        }
    }

    /**
     * Queues one barrier using standard Executor rejection semantics and waits
     * until that barrier reaches the front of the supplied serial lane.
     */
    private void awaitLaneBarrier(
            RunnableSerialLane lane,
            String laneName)
            throws InterruptedException {
        CountDownLatch barrier = new CountDownLatch(1);
        await(
                () -> {
                    try {
                        lane.execute(barrier::countDown);
                        return true;
                    } catch (RejectedExecutionException ex) {
                        return false;
                    }
                },
                laneName + " barrier execution");

        if (!barrier.await(
                COMPLETION_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for " + laneName + " barrier");
        }
    }

    private static void await(
            Condition condition,
            String description)
            throws InterruptedException {
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(
                                COMPLETION_TIMEOUT_MILLIS);
        while (!condition.ready()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(
                        "Timed out waiting for "
                                + description);
            }
            Thread.sleep(
                    5L);
        }
    }

    private static void sleepUntil(
            long targetNanos)
            throws InterruptedException {
        while (true) {
            long remaining =
                    targetNanos
                            - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }

            long millis =
                    TimeUnit.NANOSECONDS.toMillis(
                            remaining);
            int nanos =
                    (int) (remaining
                            - TimeUnit.MILLISECONDS.toNanos(
                                    millis));
            Thread.sleep(
                    millis,
                    nanos);
        }
    }

    private static EventData eventData(
            int inputCount) {
        Map<TagId, RegistrationId> mapping =
                new LinkedHashMap<TagId, RegistrationId>();
        for (int index = 0; index < inputCount; index++) {
            mapping.put(
                    new TagId(
                            tagId(
                                    index)),
                    new RegistrationId(
                            registrationId(
                                    index)));
        }
        return new EventData(
                mapping);
    }

    private static String tagId(
            int index) {
        return String.format(
                "TAG-%05d",
                index + 1);
    }

    private static String registrationId(
            int index) {
        return String.format(
                "N%05d",
                index + 1);
    }

    private static void prepareWorkDirectory(
            Path workDirectory)
            throws IOException {
        Files.createDirectories(
                workDirectory);
        Files.deleteIfExists(
                workDirectory.resolve(
                        "timing-data.jsonl"));
    }

    private static ThreadFactory threadFactory(
            String name) {
        return runnable -> {
            Thread thread =
                    new Thread(
                            runnable,
                            name);
            thread.setPriority(
                    Thread.NORM_PRIORITY);
            return thread;
        };
    }

    private static void enableThreadCpuTimeWhenSupported() {
        ThreadMXBean threads =
                ManagementFactory.getThreadMXBean();
        if (!threads.isThreadCpuTimeSupported()
                || threads.isThreadCpuTimeEnabled()) {
            return;
        }
        try {
            threads.setThreadCpuTimeEnabled(
                    true);
        } catch (SecurityException
                | UnsupportedOperationException ignored) {
            // Snapshot reports unavailable when the selected JVM rejects this.
        }
    }

    @Override
    public void close() {
        Throwable firstFailure = null;

        if (antennaManagerActive) {
            try {
                antennaLifecycle.deactivateAll();
            } catch (RuntimeException | Error failure) {
                firstFailure = failure;
            } finally {
                antennaManagerActive = false;
            }
        }

        if (nodeActive) {
            try {
                nodeLifecycle.deactivateAll();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(
                            failure);
                }
            } finally {
                nodeActive = false;
            }
        }

        shutdownNow(
                ioWorker);
        shutdownNow(
                tagWorker);
        shutdownNow(
                nodeWorker);
        composed = false;

        if (firstFailure instanceof RuntimeException) {
            throw (RuntimeException) firstFailure;
        }
        if (firstFailure instanceof Error) {
            throw (Error) firstFailure;
        }
    }

    private static void shutdownNow(
            ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    interface Condition {
        boolean ready();
    }

    static final class QueryMeasurement {
        private final int requestedLimit;
        private final long visited;
        private final int totalHistory;
        private final long elapsedNanos;

        QueryMeasurement(
                int requestedLimit,
                long visited,
                int totalHistory,
                long elapsedNanos) {
            this.requestedLimit = requestedLimit;
            this.visited = visited;
            this.totalHistory = totalHistory;
            this.elapsedNanos = elapsedNanos;
        }

        int requestedLimit() { return requestedLimit; }
        long visited() { return visited; }
        int totalHistory() { return totalHistory; }
        long elapsedNanos() { return elapsedNanos; }
    }

    static final class Result {
        private final Instant startedAt;
        private final Instant endedAt;
        private final int historyAtMeasurementStart;
        private final long measuredElapsedNanos;
        private final TimingNodeRuntimeSnapshot nodeBefore;
        private final TimingNodeRuntimeSnapshot nodeAfter;
        private final TagProcessingMetrics.Snapshot tagBefore;
        private final TagProcessingMetrics.Snapshot tagAfter;
        private final JvmRuntimeSnapshot jvmBefore;
        private final JvmRuntimeSnapshot jvmAfter;
        private final QueryMeasurement query;

        Result(
                Instant startedAt,
                Instant endedAt,
                int historyAtMeasurementStart,
                long measuredElapsedNanos,
                TimingNodeRuntimeSnapshot nodeBefore,
                TimingNodeRuntimeSnapshot nodeAfter,
                TagProcessingMetrics.Snapshot tagBefore,
                TagProcessingMetrics.Snapshot tagAfter,
                JvmRuntimeSnapshot jvmBefore,
                JvmRuntimeSnapshot jvmAfter,
                QueryMeasurement query) {
            this.startedAt = startedAt;
            this.endedAt = endedAt;
            this.historyAtMeasurementStart = historyAtMeasurementStart;
            this.measuredElapsedNanos = measuredElapsedNanos;
            this.nodeBefore = nodeBefore;
            this.nodeAfter = nodeAfter;
            this.tagBefore = tagBefore;
            this.tagAfter = tagAfter;
            this.jvmBefore = jvmBefore;
            this.jvmAfter = jvmAfter;
            this.query = query;
        }

        Instant startedAt() { return startedAt; }
        Instant endedAt() { return endedAt; }
        int historyAtMeasurementStart() { return historyAtMeasurementStart; }
        long measuredElapsedNanos() { return measuredElapsedNanos; }
        TimingNodeRuntimeSnapshot nodeBefore() { return nodeBefore; }
        TimingNodeRuntimeSnapshot nodeAfter() { return nodeAfter; }
        TagProcessingMetrics.Snapshot tagBefore() { return tagBefore; }
        TagProcessingMetrics.Snapshot tagAfter() { return tagAfter; }
        JvmRuntimeSnapshot jvmBefore() { return jvmBefore; }
        JvmRuntimeSnapshot jvmAfter() { return jvmAfter; }
        QueryMeasurement query() { return query; }
    }
}
