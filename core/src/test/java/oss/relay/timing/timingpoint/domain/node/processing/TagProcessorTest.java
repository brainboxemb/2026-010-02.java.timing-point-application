package oss.relay.timing.timingpoint.domain.node.processing;

import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingDataTypes.LocationId;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingTimestamp;
import oss.relay.timing.timingpoint.runtime.configuration.ApplicationConfiguration;
import oss.relay.timing.timingpoint.infra.configuration.ConfigurationUpdateResult;
import oss.relay.timing.timingpoint.infra.configuration.ReadOnlyConfiguration;
import oss.relay.timing.timingpoint.infra.lifecycle.LifecycleTestSupport;
import oss.relay.timing.timingpoint.platform.time.ClockTimeSource;
import oss.relay.timing.timingpoint.platform.time.TimeSource;
import oss.relay.timing.eventdata.EventData;
import oss.relay.timing.eventdata.TagId;
import oss.relay.timing.timingpoint.domain.node.TimingNode;
import oss.relay.timing.timingpoint.testsupport.TimingNodeFixture;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookCommands;
import oss.relay.timing.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import oss.relay.timing.timingpoint.io.devices.antenna.TagObservation;
import oss.relay.timing.timingpoint.platform.environment.MonotonicClock;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import static oss.relay.timing.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static oss.relay.timing.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TagProcessorTest {
    private final List<ExecutorService> ownedWorkers =
            new ArrayList<ExecutorService>();

    @After
    public void stopOwnedWorkers() {
        for (ExecutorService worker : ownedWorkers) {
            worker.shutdownNow();
        }
    }

    private TagExecution newTagExecution(
            String threadName) {
        ScheduledThreadPoolExecutor worker =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> new Thread(runnable, threadName));
        worker.setRemoveOnCancelPolicy(true);
        ownedWorkers.add(worker);
        return new TagExecution(worker);
    }

    private static final TimingTimestamp OBSERVED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp STRONGER_OBSERVED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:00.050000000Z");
    private static final TimingTimestamp NEXT_PASSAGE_OBSERVED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:00.150000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    @Test
    public void eventCallbackQueuesUnmappedObservationForLaneProcessing()
            throws Exception {
        TimingNode node = node(new RecordingStore());
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagExecution execution =
                newTagExecution("tp-tag-test");
        TagProcessingMetrics metrics =
                new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                                EventData.empty(),
                policy(8),
                clock,
                metrics,
                execution.executor);

        activate(processor);
        try {
            processor.onTagObserved(
                    observation("N0001A", -42, OBSERVED_AT));
            awaitExecutor(execution.executor);

            assertEquals(
                    1L,
                    metrics.snapshot().unmapped());
        } finally {
            deactivate(processor);
        }
    }

    @Test
    public void differentTagsForSameRegistrationShareOneScheduledPassage()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagExecution execution =
                newTagExecution("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                                eventDataFor(
                        "N0001A", "N0001",
                        "N0001B", "N0001",
                        "N0001A", "N0001"),
                policy(8),
                clock,
                metrics,
                execution.executor);
        connectToNode(processor, node);
        CountDownLatch committed = new CountDownLatch(1);
        node.NEW_DATA_ENTRY.subscribe(data -> {
            if (data.kind() == TimingRecord.Kind.AUTOMATIC_REGISTRATION) {
                committed.countDown();
            }
        });

        activate(node);
        node.open(new LocationId(24));
        activate(processor);
        try {
            processor.onTagObserved(
                    observation("N0001A", -60, OBSERVED_AT));
            processor.onTagObserved(
                    observation("N0001B", -30, STRONGER_OBSERVED_AT));
            awaitExecutor(execution.executor);

            assertTrue(
                    processor.hasPendingPassages());

            clock.advanceMillis(150L);

            assertTrue(committed.await(1, TimeUnit.SECONDS));
            awaitExecutor(execution.executor);
            assertFalse(
                    processor.hasPendingPassages());
            assertEquals(2, store.appended.size());
            TimingRecord registration = store.appended.get(1);
            assertEquals(
                    new RegistrationId("N0001").packedValue(),
                    registration.registrationId());
            assertEquals(
                    STRONGER_OBSERVED_AT.instant().toEpochMilli(),
                    registration.effectiveTimeMillis());

            TagProcessingMetrics.Snapshot snapshot = metrics.snapshot();
            assertEquals(2L, snapshot.observations());
            assertEquals(2L, snapshot.mapped());
            assertEquals(1L, snapshot.closedPassages());
        } finally {
            deactivate(processor);
            deactivate(node);
        }
    }

    @Test
    public void selectedRegistrationSuppressesLaterMappedObservationsBeforePassageFiltering()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagExecution execution =
                newTagExecution("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                                eventDataFor(
                        "N0001A", "N0001",
                        "N0001B", "N0001",
                        "N0001A", "N0001"),
                policy(8),
                clock,
                metrics,
                execution.executor);
        connectToNode(processor, node);
        CountDownLatch committed = new CountDownLatch(1);
        node.NEW_DATA_ENTRY.subscribe(data -> {
            if (data.kind() == TimingRecord.Kind.AUTOMATIC_REGISTRATION) {
                committed.countDown();
            }
        });

        activate(node);
        node.open(new LocationId(24));
        activate(processor);
        try {
            processor.onTagObserved(
                    observation("N0001A", -60, OBSERVED_AT));
            awaitExecutor(execution.executor);

            /*
             * The observed 150 ms gap closes the first passage. The filter has
             * already started the next passage for N0001B before publishing the
             * selected registration. Duplicate suppression then discards that
             * just-started next passage.
             */
            processor.onTagObserved(
                    observation("N0001B", -50, NEXT_PASSAGE_OBSERVED_AT));
            awaitExecutor(execution.executor);
            assertTrue(committed.await(1, TimeUnit.SECONDS));

            processor.onTagObserved(
                    observation("N0001A", -40, STRONGER_OBSERVED_AT));
            awaitExecutor(execution.executor);

            TagProcessingMetrics.Snapshot snapshot = metrics.snapshot();
            assertEquals(3L, snapshot.observations());
            assertEquals(3L, snapshot.mapped());
            assertEquals(1L, snapshot.closedPassages());
            assertEquals(1L, snapshot.duplicates());
            assertEquals(2, store.appended.size());
        } finally {
            deactivate(processor);
            deactivate(node);
        }
    }

    @Test
    public void selectedRegistrationStartsDuplicateSuppressionWithoutDownstreamFeedback()
            throws Exception {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagExecution execution =
                newTagExecution("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                eventDataFor(
                        "N0001A", "N0001",
                        "N0001B", "N0001",
                        "N0001A", "N0001"),
                policy(8),
                clock,
                metrics,
                execution.executor);
        CountDownLatch registrationPublished =
                new CountDownLatch(1);
        processor.NEW_REGISTRATION
                .subscribe(
                        ignored -> registrationPublished.countDown());

        activate(processor);
        try {
            processor.onTagObserved(
                    observation("N0001A", -60, OBSERVED_AT));
            awaitExecutor(execution.executor);

            processor.onTagObserved(
                    observation("N0001B", -50, NEXT_PASSAGE_OBSERVED_AT));
            awaitExecutor(execution.executor);
            assertTrue(
                    registrationPublished.await(
                            1,
                            TimeUnit.SECONDS));

            processor.onTagObserved(
                    observation("N0001A", -40, NEXT_PASSAGE_OBSERVED_AT));
            awaitExecutor(execution.executor);

            TagProcessingMetrics.Snapshot snapshot = metrics.snapshot();
            assertEquals(3L, snapshot.mapped());
            assertEquals(1L, snapshot.duplicates());
            assertEquals(1L, snapshot.closedPassages());
        } finally {
            deactivate(processor);
        }
    }

    @Test
    public void runtimePolicyOverrideReevaluatesExistingPassage()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagExecution execution =
                newTagExecution("tp-tag-test");
        TagProcessingPolicy startup = policy(8);
        ApplicationConfiguration configuration =
                ApplicationConfiguration.singleTimingNode(
                        new NodeId("A"),
                        startup);
        TagProcessor processor = new TagProcessor(
                                eventDataFor(
                        "N0001A", "N0001",
                        "N0001B", "N0001",
                        "N0001A", "N0001"),
                configuration.timingNode(new NodeId("A")).tagProcessing(),
                clock,
                new TagProcessingMetrics(),
                execution.executor);
        connectToNode(processor, node);
        CountDownLatch committed = new CountDownLatch(1);
        node.NEW_DATA_ENTRY.subscribe(data -> {
            if (data.kind() == TimingRecord.Kind.AUTOMATIC_REGISTRATION) {
                committed.countDown();
            }
        });

        activate(node);
        node.open(new LocationId(24));
        activate(processor);
        try {
            processor.onTagObserved(
                    observation("N0001A", -50, OBSERVED_AT));
            awaitExecutor(execution.executor);

            clock.advanceMillis(50L);

            TagProcessingPolicy shorterQuietWindow =
                    new TagProcessingPolicy(
                            Duration.ofMillis(40L),
                            Duration.ofMillis(500L),
                            Duration.ofMillis(1000L),
                            Duration.ofMillis(5L),
                            8);

            assertEquals(
                    ConfigurationUpdateResult.APPLIED,
                    configuration
                            .timingNode(new NodeId("A"))
                            .tagProcessing()
                            .override(shorterQuietWindow));

            assertTrue(committed.await(1, TimeUnit.SECONDS));
            assertEquals(2, store.appended.size());
        } finally {
            deactivate(processor);
            deactivate(node);
        }
    }

    @Test
    public void idleProcessorDoesNotScheduleFilterOrHousekeepingRuns()
            throws Exception {
        RecordingScheduledExecutor executor = new RecordingScheduledExecutor();
        TagProcessor processor = new TagProcessor(
                eventDataFor("N0001A", "N0001"),
                policy(8),
                new FakeMonotonicClock(),
                new TagProcessingMetrics(),
                executor);

        activate(processor);
        try {
            awaitExecutor(executor);
            assertTrue(executor.scheduledOperations.isEmpty());
        } finally {
            deactivate(processor);
            executor.closeWorker();
        }
    }

    @Test
    public void firstObservationSchedulesOneFilterRun()
            throws Exception {
        RecordingScheduledExecutor executor = new RecordingScheduledExecutor();
        TagProcessor processor = new TagProcessor(
                eventDataFor("N0001A", "N0001"),
                policy(8),
                new FakeMonotonicClock(),
                new TagProcessingMetrics(),
                executor);

        activate(processor);
        try {
            processor.onTagObserved(observation("N0001A", -50, OBSERVED_AT));
            awaitExecutor(executor);

            assertEquals(1, executor.scheduledOperations.size());
        } finally {
            deactivate(processor);
            executor.closeWorker();
        }
    }

    @Test
    public void deactivationCancelsPendingFilterRun()
            throws Exception {
        RecordingScheduledExecutor executor = new RecordingScheduledExecutor();
        TagProcessor processor = new TagProcessor(
                eventDataFor("N0001A", "N0001"),
                policy(8),
                new FakeMonotonicClock(),
                new TagProcessingMetrics(),
                executor);

        activate(processor);
        processor.onTagObserved(observation("N0001A", -50, OBSERVED_AT));
        awaitExecutor(executor);

        assertEquals(1, executor.scheduledFutures.size());
        deactivate(processor);

        assertTrue(executor.scheduledFutures.get(0).isCancelled());
        executor.closeWorker();
    }

    @Test
    public void runtimeCadenceOverrideReplacesPendingFilterRun()
            throws Exception {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RecordingScheduledExecutor executor = new RecordingScheduledExecutor();
        TagProcessingPolicy startup = policy(8);
        ApplicationConfiguration configuration =
                ApplicationConfiguration.singleTimingNode(
                        new NodeId("A"),
                        startup);
        TagProcessor processor = new TagProcessor(
                referenceEventData(),
                configuration.timingNode(new NodeId("A")).tagProcessing(),
                clock,
                new TagProcessingMetrics(),
                executor);

        activate(processor);
        try {
            processor.onTagObserved(observation("N0001A", -42, OBSERVED_AT));
            awaitExecutor(executor);
            assertEquals(1, executor.scheduledOperations.size());

            TagProcessingPolicy fasterSweep =
                    new TagProcessingPolicy(
                            Duration.ofNanos(100L),
                            Duration.ofNanos(500L),
                            Duration.ofNanos(1000L),
                            Duration.ofMillis(2L),
                            8);
            assertEquals(
                    ConfigurationUpdateResult.APPLIED,
                    configuration
                            .timingNode(new NodeId("A"))
                            .tagProcessing()
                            .override(fasterSweep));

            awaitExecutor(executor);
            assertEquals(2, executor.scheduledOperations.size());
            assertTrue(executor.scheduledFutures.get(0).isCancelled());
        } finally {
            deactivate(processor);
            executor.closeWorker();
        }
    }

    @Test
    public void boundedObservationQueueReportsOverload() throws Exception {
        TimingNode node = node(new RecordingStore());
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagExecution execution =
                newTagExecution("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                                referenceEventData(),
                policy(1),
                clock,
                metrics,
                execution.executor);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        activate(processor);
        try {
            execution.executor.execute(() -> {
                        blockerStarted.countDown();
                        await(releaseBlocker);
                    });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            processor.onTagObserved(
                    observation("N0001A", -42, OBSERVED_AT));
            processor.onTagObserved(
                    observation("N0002A", -41, OBSERVED_AT));

            assertEquals(
                    1L,
                    metrics.snapshot().observationQueueFull());
        } finally {
            releaseBlocker.countDown();
            deactivate(processor);
        }
    }

    @Test
    public void stopDrainsAcceptedInputWithoutForceClosingPassage()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagExecution execution =
                newTagExecution("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                                referenceEventData(),
                policy(4),
                clock,
                metrics,
                execution.executor);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        activate(processor);
        execution.executor.execute(() -> {
                    blockerStarted.countDown();
                    await(releaseBlocker);
                });
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

        processor.onTagObserved(
                observation("N0001A", -42, OBSERVED_AT));

        Thread stopper = new Thread(() -> LifecycleTestSupport.deactivate(processor));
        stopper.start();
        releaseBlocker.countDown();
        stopper.join(1000);

        assertFalse(stopper.isAlive());
        assertEquals(1L, metrics.snapshot().mapped());
        assertTrue(store.appended.isEmpty());
    }

    private static TagProcessingPolicy policy(int queueCapacity) {
        return new TagProcessingPolicy(
                Duration.ofMillis(100L),
                Duration.ofMillis(500L),
                Duration.ofMillis(1000L),
                Duration.ofMillis(5L),
                queueCapacity);
    }

    private static TagObservation observation(
            String tagId,
            int rssi,
            TimingTimestamp observedAt) {
        long observedAtEpochMillis = observedAt.instant().toEpochMilli();
        long baseEpochMillis = OBSERVED_AT.instant().toEpochMilli();
        return new TagObservation(
                new TagId(tagId),
                rssi,
                observedAtEpochMillis,
                TimeUnit.MILLISECONDS.toNanos(
                        observedAtEpochMillis - baseEpochMillis));
    }

    private static EventData referenceEventData() {
        return eventDataFor(
                "N0001A", "N0001",
                "N0002A", "N0002");
    }

    private static EventData eventDataFor(
            String... tagAndRegistrationIds) {
        if (tagAndRegistrationIds.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "tagAndRegistrationIds must contain pairs");
        }

        Map<TagId, RegistrationId> registrations =
                new LinkedHashMap<TagId, RegistrationId>();
        for (int index = 0;
                index < tagAndRegistrationIds.length;
                index += 2) {
            registrations.put(
                    new TagId(
                            tagAndRegistrationIds[index]),
                    new RegistrationId(
                            tagAndRegistrationIds[index + 1]));
        }
        return new EventData(
                registrations);
    }

    private static void connectToNode(
            TagProcessor processor,
            TimingNode node) {
        processor.NEW_REGISTRATION
                .subscribe(
                        registration ->
                                node.logBook().execute(
                                        LogBookCommands.commitAntennaRegistration(
                                                node.locationId(),
                                                registration.registrationId(),
                                                registration.observedAtEpochMillis())));
    }

    private static void awaitExecutor(ScheduledExecutorService executor)
            throws Exception {
        CountDownLatch barrier = new CountDownLatch(1);
        executor.execute(barrier::countDown);
        assertTrue(barrier.await(1, TimeUnit.SECONDS));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting test", ex);
        }
    }

    private TimingNode node(RecordingStore store) {
        TimeSource timeSource =
                new ClockTimeSource(
                        Clock.fixed(
                                RECORDED_AT.instant(),
                                ZoneOffset.UTC));
        return TimingNodeFixture.create(
                new NodeId("A"),
                store,
                timeSource);
    }

    private static final class RecordingScheduledExecutor
            extends ScheduledThreadPoolExecutor {
        private final List<Runnable> scheduledOperations = new ArrayList<Runnable>();
        private final List<ScheduledFuture<?>> scheduledFutures =
                new ArrayList<ScheduledFuture<?>>();

        private RecordingScheduledExecutor() {
            super(1);
            setRemoveOnCancelPolicy(true);
        }

        /** Records delayed Job runs while preserving normal zero-delay executor work. */
        @Override
        public ScheduledFuture<?> schedule(
                Runnable operation,
                long delay,
                TimeUnit unit) {
            if (delay <= 0L) {
                return super.schedule(operation, delay, unit);
            }

            scheduledOperations.add(operation);
            ScheduledFuture<?> future =
                    super.schedule(
                            () -> { },
                            1L,
                            TimeUnit.DAYS);
            scheduledFutures.add(future);
            return future;
        }

        private void closeWorker() {
            shutdownNow();
        }
    }

    private static final class TagExecution {
        private final ScheduledThreadPoolExecutor executor;

        private TagExecution(ScheduledThreadPoolExecutor executor) {
            this.executor = executor;
        }
    }

    private static final class FakeMonotonicClock implements MonotonicClock {
        private volatile long now;

        @Override
        public long nowNanos() {
            return now;
        }

        private void advanceMillis(long millis) {
            now += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }

    private static final class RecordingStore implements TimingDataPersistence {
        private final List<TimingRecord> appended = new ArrayList<>();

        @Override
        public LoadResult load() {
            return new LoadResult(new ArrayList<TimingRecord>(), false);
        }

        @Override
        public void append(TimingRecord record) {
            appended.add(record);
        }
    }
}
