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
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;
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
import java.util.concurrent.ExecutorService;
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
    private static final Instant INPUT_TIME_BASE =
            Instant.parse("2026-01-01T00:00:00Z");

    private final CharacterizationConfig config;
    private final ExecutorService nodeWorker;
    private final ScheduledThreadPoolExecutor tagWorker;
    private final ScheduledThreadPoolExecutor ioWorker;
    private final SerialExecutor nodeLane;
    private final SerialScheduledExecutor tagLane;
    private final SerialScheduledExecutor antennaLane;
    private final SimulatedAntenna antenna;
    private final AntennaManager antennaManager;
    private final TimingNode node;
    private final RuntimeMeasurementReader measurements;
    private final AtomicLong committedCount = new AtomicLong();

    private boolean active;

    RuntimeCharacterizationHarness(
            CharacterizationConfig config)
            throws IOException {
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null");
        }
        this.config = config;

        prepareWorkDirectory(
                config.workDirectory());

        nodeWorker =
                Executors.newSingleThreadExecutor(
                        threadFactory(
                                "tp-dml-node-worker"));
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

        nodeLane =
                new SerialExecutor(
                        TIMING_NODE_QUEUE_CAPACITY,
                        "TimingNode",
                        nodeWorker);
        tagLane =
                new SerialScheduledExecutor(
                        TAG_PROCESSOR_QUEUE_CAPACITY,
                        "TagProcessor",
                        tagWorker);
        antennaLane =
                new SerialScheduledExecutor(
                        ANTENNA_CONTROL_QUEUE_CAPACITY,
                        "AntennaManager",
                        ioWorker);

        TimingNodeMetrics nodeMetrics =
                new TimingNodeMetrics();
        TagProcessingMetrics tagMetrics =
                new TagProcessingMetrics();

        TimingDataProvider timingDataProvider =
                new DefaultTimingDataProvider();

        node =
                new TimingNode(
                        NODE_ID,
                        new DefaultTimingDataPersistence(
                                new FileAppendOnlyRecordStore(
                                        config.workDirectory()
                                                .resolve(
                                                        "timing-data.jsonl")),
                                NODE_ID,
                                timingDataProvider.createCodec()),
                        timingDataProvider.createFactory(),
                        new ClockTimeSource(
                                Clock.systemUTC()),
                        ReadOnlyConfiguration.fixed(
                                TagProcessingPolicy.defaults()),
                        eventData(
                                config.warmupCount()
                                        + config.measuredCount()),
                        nodeLane,
                        tagLane,
                        SystemMonotonicClock.INSTANCE,
                        nodeMetrics,
                        tagMetrics);

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
                        antennaLane,
                        ANTENNA_CONTROL_TIMEOUT);

        antennaManager
                .tagObservedEvent(
                        ANTENNA_ID)
                .subscribe(
                        node.tagProcessor()::onTagObserved);
        node.timingDataCommittedEvent()
                .subscribe(
                        ignored ->
                                committedCount.incrementAndGet());

        measurements =
                new RuntimeMeasurementReader(
                        nodeLane.metrics(),
                        nodeMetrics,
                        tagMetrics);

        enableThreadCpuTimeWhenSupported();
    }

    Result run()
            throws Exception {
        Instant startedAt =
                Instant.now();

        node.activate();
        active = true;
        node.invoke(
                TimingNodeCommands.open(
                        LOCATION_ID));

        preloadHistory(
                config.preloadCount());

        antennaManager.activate();
        await(
                antennaManager::isReady,
                "AntennaManager ready");
        if (!antennaManager.setInventoryEnabled(
                true)) {
            throw new IllegalStateException(
                    "AntennaManager rejected desired inventory state");
        }
        await(
                antenna::inventoryRunning,
                "simulated antenna inventory");

        runInput(
                0,
                config.warmupCount(),
                false);
        awaitCommitted(
                config.preloadCount()
                        + config.warmupCount());

        int historyAtMeasurementStart =
                node.query(
                        TimingNodeQueries.timingDataCount());

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
                config.workload()
                        != CharacterizationConfig.Workload.BURST);
        awaitCommitted(
                config.preloadCount()
                        + config.warmupCount()
                        + config.measuredCount());
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
            if (!node.invoke(
                            TimingNodeCommands.addAutomaticRegistration(
                                    new RegistrationId(
                                            String.format(
                                                    "P%05d",
                                                    index + 1)),
                                    time))
                    .committed()) {
                throw new IllegalStateException(
                        "Unable to preload TimingData record "
                                + (index + 1));
            }
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
                node.query(
                        TimingNodeQueries.visitLatestTimingData(
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

    private void awaitCommitted(
            long expected)
            throws InterruptedException {
        await(
                () -> committedCount.get() >= expected,
                "TimingData commit count " + expected);
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
        if (active) {
            try {
                antennaManager.deactivate();
            } finally {
                node.deactivate();
            }
            active = false;
        }

        ioWorker.shutdownNow();
        tagWorker.shutdownNow();
        nodeWorker.shutdownNow();
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
