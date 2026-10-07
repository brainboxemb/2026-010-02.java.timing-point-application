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
import io.github.brainboxemb.eventtiming.timingpoint.application.Conductor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutorMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.ClockTimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.JvmRuntimeSnapshot;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.RuntimeMeasurementReader;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.TimingNodeRuntimeSnapshot;

import java.io.IOException;
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
import java.util.function.BooleanSupplier;

/**
 * Engineering-only deterministic runtime characterization harness.
 *
 * <p>The measured input path is the same composed product path used by the
 * simulated application: SimulatedAntenna -> AntennaManager event -> TagProcessor
 * -> TimingNode.offer -> persistence/LogBook. Direct TimingNode commands are used
 * only for setup/history preload and lifecycle.</p>
 */
final class RuntimeCharacterization {
    private static final NodeId NODE_ID =
            new NodeId("A");
    private static final AntennaId ANTENNA_ID =
            new AntennaId("1");
    private static final LocationId LOCATION_ID =
            new LocationId(24);
    private static final int TIMING_NODE_QUEUE_CAPACITY = 32;
    private static final int TAG_PROCESSOR_QUEUE_CAPACITY = 32;
    private static final int APPLICATION_QUEUE_CAPACITY = 8;
    private static final int ANTENNA_QUEUE_CAPACITY = 8;
    private static final Duration ANTENNA_CONTROL_TIMEOUT =
            Duration.ofSeconds(2);
    private static final Instant OBSERVATION_BASE =
            Instant.parse("2026-10-07T00:00:00Z");

    private final HarnessBuildIdentity build =
            HarnessBuildIdentity.load();
    private final CharacterizationEvidenceWriter evidenceWriter =
            new CharacterizationEvidenceWriter();

    Path runOne(
            CharacterizationOptions options,
            int repetition)
            throws Exception {
        Instant startedAt =
                Instant.now();
        RunContext context =
                new RunContext(
                        options,
                        repetition);

        try {
            context.start();
            context.preloadHistory();
            context.runWarmup();

            TimingNodeRuntimeSnapshot nodeBefore =
                    context.reader.timingNode();
            TagProcessingMetrics.Snapshot tagBefore =
                    context.reader.tagProcessing();
            JvmRuntimeSnapshot jvmBefore =
                    context.reader.jvm();

            long measuredStartedNanos =
                    System.nanoTime();
            context.runMeasured();
            long measuredDurationNanos =
                    elapsedNanos(
                            measuredStartedNanos,
                            System.nanoTime());

            TimingNodeRuntimeSnapshot nodeAfter =
                    context.reader.timingNode();
            TagProcessingMetrics.Snapshot tagAfter =
                    context.reader.tagProcessing();
            JvmRuntimeSnapshot jvmAfter =
                    context.reader.jvm();

            long queryStartedNanos =
                    System.nanoTime();
            int finalCount =
                    context.node.query(
                            TimingNodeQueries.visitLatestTimingData(
                                    Math.min(
                                            100,
                                            Math.max(
                                                    1,
                                                    options.preloadedHistory()
                                                            + options.warmupObservations()
                                                            + options.observations())),
                                    ignored -> {
                                        // Bounded no-op visitor measures traversal only.
                                    }));
            long historyQueryNanos =
                    elapsedNanos(
                            queryStartedNanos,
                            System.nanoTime());

            CharacterizationRunResult result =
                    new CharacterizationRunResult(
                            options,
                            build,
                            repetition,
                            startedAt,
                            Instant.now(),
                            measuredDurationNanos,
                            nodeBefore,
                            nodeAfter,
                            tagBefore,
                            tagAfter,
                            jvmBefore,
                            jvmAfter,
                            historyQueryNanos,
                            finalCount);

            return evidenceWriter.write(
                    result);
        } catch (Throwable failure) {
            evidenceWriter.writeFailure(
                    options,
                    build,
                    repetition,
                    startedAt,
                    failure);
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            throw (Error) failure;
        } finally {
            context.close();
        }
    }

    private static long elapsedNanos(
            long started,
            long finished) {
        long elapsed =
                finished - started;
        return elapsed < 0L ? 0L : elapsed;
    }

    private static final class RunContext
            implements AutoCloseable {
        private final CharacterizationOptions options;
        private final int repetition;

        private final ExecutorService nodeWorker =
                Executors.newSingleThreadExecutor(
                        namedThreadFactory(
                                "tp-dml-node-worker"));
        private final ScheduledThreadPoolExecutor tagWorker =
                scheduledWorker(
                        "tp-dml-tagproc-worker");
        private final ExecutorService applicationWorker =
                Executors.newSingleThreadExecutor(
                        namedThreadFactory(
                                "tp-apl-worker"));
        private final ScheduledThreadPoolExecutor ioWorker =
                scheduledWorker(
                        "tp-io-shared-worker");

        private final SerialExecutor nodeLane =
                new SerialExecutor(
                        TIMING_NODE_QUEUE_CAPACITY,
                        "TimingNode",
                        nodeWorker);
        private final SerialScheduledExecutor tagLane =
                new SerialScheduledExecutor(
                        TAG_PROCESSOR_QUEUE_CAPACITY,
                        "TagProcessor",
                        tagWorker);
        private final SerialExecutor applicationLane =
                new SerialExecutor(
                        APPLICATION_QUEUE_CAPACITY,
                        "Conductor",
                        applicationWorker);
        private final SerialScheduledExecutor antennaLane =
                new SerialScheduledExecutor(
                        ANTENNA_QUEUE_CAPACITY,
                        "AntennaManager",
                        ioWorker);

        private final TimingNodeMetrics nodeMetrics =
                new TimingNodeMetrics();
        private final TagProcessingMetrics tagMetrics =
                new TagProcessingMetrics();
        private final AtomicLong committedCount =
                new AtomicLong();
        private final SimulatedAntenna antenna =
                new SimulatedAntenna();
        private final AntennaManager antennaManager;
        private final TimingNode node;
        private final Conductor conductor;
        private final RuntimeMeasurementReader reader;

        private boolean started;

        private RunContext(
                CharacterizationOptions options,
                int repetition)
                throws IOException {
            this.options = options;
            this.repetition = repetition;

            Files.createDirectories(
                    options.evidenceDirectory());
            Path workDirectory =
                    options.evidenceDirectory()
                            .resolve(
                                    "work-r" + repetition);
            Files.createDirectories(
                    workDirectory);
            Path timingDataFile =
                    workDirectory.resolve(
                            "timing-data.jsonl");
            Files.deleteIfExists(
                    timingDataFile);

            TimingDataProvider timingDataProvider =
                    new DefaultTimingDataProvider();
            TimingDataPersistence persistence =
                    new DefaultTimingDataPersistence(
                            new FileAppendOnlyRecordStore(
                                    timingDataFile),
                            NODE_ID,
                            timingDataProvider.createCodec());
            TimeSource timeSource =
                    new ClockTimeSource(
                            Clock.systemUTC());

            node =
                    new TimingNode(
                            NODE_ID,
                            persistence,
                            timingDataProvider.createFactory(),
                            timeSource,
                            ReadOnlyConfiguration.fixed(
                                    TagProcessingPolicy.defaults()),
                            eventData(
                                    options.warmupObservations()
                                            + options.observations()),
                            nodeLane,
                            tagLane,
                            SystemMonotonicClock.INSTANCE,
                            nodeMetrics,
                            tagMetrics);

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
            conductor =
                    new Conductor(
                            node,
                            antennaManager,
                            applicationLane);

            node.statusChangedEvent()
                    .subscribe(
                            ignored ->
                                    conductor
                                            .timingNodeStateProperty()
                                            .signalChanged());
            antennaManager
                    .tagObservedEvent(
                            ANTENNA_ID)
                    .subscribe(
                            node.tagProcessor()::onTagObserved);
            node.timingDataCommittedEvent()
                    .subscribe(
                            ignored ->
                                    committedCount.incrementAndGet());

            reader =
                    new RuntimeMeasurementReader(
                            nodeLane.metrics(),
                            nodeMetrics,
                            tagMetrics);
        }

        private void start()
                throws Exception {
            conductor.activate();
            started = true;

            await(
                    antennaManager::isReady,
                    5000L,
                    "antenna self-test readiness");

            node.invoke(
                    TimingNodeCommands.open(
                            LOCATION_ID));

            await(
                    antenna::inventoryRunning,
                    5000L,
                    "antenna inventory start");
        }

        private void preloadHistory()
                throws Exception {
            for (int index = 0;
                    index < options.preloadedHistory();
                    index++) {
                TimingData result =
                        node.invoke(
                                        TimingNodeCommands.addAutomaticRegistration(
                                                new RegistrationId(
                                                        String.format(
                                                                "H-%06d",
                                                                index + 1)),
                                                timestamp(
                                                        -options.preloadedHistory()
                                                                + index)))
                                .timingData();
                if (result == null) {
                    throw new IllegalStateException(
                            "history preload did not commit record "
                                    + index);
                }
            }
        }

        private void runWarmup()
                throws Exception {
            if (options.warmupObservations() == 0) {
                return;
            }
            emitObservations(
                    0,
                    options.warmupObservations(),
                    true);
        }

        private void runMeasured()
                throws Exception {
            boolean paced =
                    options.workload()
                            != CharacterizationOptions.Workload.BURST;
            emitObservations(
                    options.warmupObservations(),
                    options.observations(),
                    paced);
        }

        private void emitObservations(
                int firstIndex,
                int count,
                boolean paced)
                throws Exception {
            long expectedCommits =
                    committedCount.get()
                            + count;
            long intervalNanos =
                    (long) (1_000_000_000.0d
                            / options.ratePerSecond());
            long nextEmission =
                    System.nanoTime();

            for (int offset = 0;
                    offset < count;
                    offset++) {
                int index =
                        firstIndex + offset;

                if (paced && offset > 0) {
                    nextEmission += intervalNanos;
                    sleepUntil(
                            nextEmission);
                }

                antenna.emit(
                        tagId(index),
                        -45,
                        timestamp(index));
            }

            long workloadMillis =
                    paced
                            ? (long) Math.ceil(
                                    1000.0d
                                            * count
                                            / options.ratePerSecond())
                            : 0L;
            long timeoutMillis =
                    Math.max(
                            5000L,
                            workloadMillis + 5000L);
            await(
                    () ->
                            committedCount.get()
                                    >= expectedCommits,
                    timeoutMillis,
                    "TimingData commits");

            /*
             * TimingDataCommitted is emitted inside the TimingNode task. Wait
             * until SerialExecutor has also accounted that task as completed
             * before taking the phase-end measurement snapshot.
             */
            await(
                    () -> {
                        SerialExecutorMetrics.Snapshot lane =
                                nodeLane.metrics()
                                        .snapshot();
                        return lane.queueDepth() == 0
                                && lane.completedCount()
                                        == lane.acceptedCount();
                    },
                    timeoutMillis,
                    "TimingNode lane completion");
        }

        @Override
        public void close() {
            if (started) {
                try {
                    conductor.deactivate();
                } catch (RuntimeException ignored) {
                    // Preserve the characterization failure, if any.
                }
            }
            ioWorker.shutdownNow();
            applicationWorker.shutdownNow();
            tagWorker.shutdownNow();
            nodeWorker.shutdownNow();
        }

        private static EventData eventData(
                int observationCount) {
            Map<TagId, RegistrationId> registrations =
                    new LinkedHashMap<TagId, RegistrationId>();
            for (int index = 0;
                    index < observationCount;
                    index++) {
                registrations.put(
                        tagId(index),
                        new RegistrationId(
                                String.format(
                                        "R-%06d",
                                        index + 1)));
            }
            return new EventData(
                    registrations);
        }

        private static TagId tagId(
                int index) {
            return new TagId(
                    String.format(
                            "TAG-%06d",
                            index + 1));
        }

        private static TimingTimestamp timestamp(
                int index) {
            return new TimingTimestamp(
                    OBSERVATION_BASE.plusMillis(
                            index));
        }

        private static ScheduledThreadPoolExecutor scheduledWorker(
                String name) {
            ScheduledThreadPoolExecutor worker =
                    new ScheduledThreadPoolExecutor(
                            1,
                            namedThreadFactory(
                                    name));
            worker.setRemoveOnCancelPolicy(
                    true);
            return worker;
        }

        private static ThreadFactory namedThreadFactory(
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

        private static void sleepUntil(
                long deadlineNanos)
                throws InterruptedException {
            while (true) {
                long remaining =
                        deadlineNanos
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

        private static void await(
                BooleanSupplier condition,
                long timeoutMillis,
                String description)
                throws InterruptedException {
            long deadline =
                    System.nanoTime()
                            + TimeUnit.MILLISECONDS.toNanos(
                                    timeoutMillis);
            while (!condition.getAsBoolean()) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException(
                            "Timed out waiting for "
                                    + description);
                }
                Thread.sleep(
                        5L);
            }
        }
    }
}
