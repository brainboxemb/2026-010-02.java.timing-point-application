package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.AutomaticRegistration;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ConfigurationUpdateResult;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.ScheduledSerialLane;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
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

    private SerialLane newSerialLane(
            int capacity,
            String threadName) {
        ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        runnable -> new Thread(runnable, threadName));
        ownedWorkers.add(worker);
        return new SerialLane(capacity, capacity, threadName, worker);
    }

    private TagExecution newTagExecution(
            String threadName) {
        ScheduledThreadPoolExecutor worker =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> new Thread(runnable, threadName));
        worker.setRemoveOnCancelPolicy(true);
        ownedWorkers.add(worker);
        return new TagExecution(
                new ScheduledSerialLane(
                        32,
                        32,
                        threadName,
                        worker));
    }

    private static final TimingTimestamp OBSERVED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp STRONGER_OBSERVED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:00.050000000Z");
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
                execution.lane);

        activate(processor);
        try {
            processor.onTagObserved(
                    observation("TAG-001", -42, OBSERVED_AT));
            awaitLane(execution.lane);

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
                        "TAG-A", "N-001",
                        "TAG-B", "N-001",
                        "TAG-C", "N-001"),
                policy(8),
                clock,
                metrics,
                execution.lane);
        connectToNode(processor, node);
        CountDownLatch committed = new CountDownLatch(1);
        node.logBook().newDataEntryEvent().subscribe(data -> {
            if (data instanceof AutomaticRegistration) {
                committed.countDown();
            }
        });

        activate(node);
        node.open(new LocationId(24));
        activate(processor);
        try {
            processor.onTagObserved(
                    observation("TAG-A", -60, OBSERVED_AT));
            processor.onTagObserved(
                    observation("TAG-B", -30, STRONGER_OBSERVED_AT));
            awaitLane(execution.lane);

            List<TagPassageSnapshot> passages =
                    processor.passageSnapshots();
            assertEquals(1, passages.size());
            assertEquals(
                    new RegistrationId("N-001"),
                    passages.get(0).registrationId());
            assertEquals(
                    new TagId("TAG-B"),
                    passages.get(0).selectedTagId());
            assertEquals(2, passages.get(0).tags().size());

            clock.advanceNanos(100L);

            assertTrue(committed.await(1, TimeUnit.SECONDS));
            awaitLane(execution.lane);
            assertEquals(2, store.appended.size());
            AutomaticRegistration registration =
                    (AutomaticRegistration) store.appended.get(1);
            assertEquals(
                    new RegistrationId("N-001"),
                    registration.registrationId());
            assertEquals(
                    STRONGER_OBSERVED_AT,
                    registration.effectiveTime());

            TagProcessingMetrics.Snapshot snapshot = metrics.snapshot();
            assertEquals(2L, snapshot.observations());
            assertEquals(2L, snapshot.mapped());
            assertEquals(1L, snapshot.closedBursts());
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
                        "TAG-A", "N-001",
                        "TAG-B", "N-001",
                        "TAG-C", "N-001"),
                policy(8),
                clock,
                metrics,
                execution.lane);
        connectToNode(processor, node);
        CountDownLatch committed = new CountDownLatch(1);
        node.logBook().newDataEntryEvent().subscribe(data -> {
            if (data instanceof AutomaticRegistration) {
                committed.countDown();
            }
        });

        activate(node);
        node.open(new LocationId(24));
        activate(processor);
        try {
            processor.onTagObserved(
                    observation("TAG-A", -60, OBSERVED_AT));
            awaitLane(execution.lane);

            /*
             * This closes the first passage. TagObservationFilter has already
             * created a new burst for TAG-B before the selected registration
             * is published. Selecting that registration starts duplicate
             * suppression and discards the just-created next burst.
             */
            clock.advanceNanos(100L);
            processor.onTagObserved(
                    observation("TAG-B", -50, STRONGER_OBSERVED_AT));
            awaitLane(execution.lane);
            assertTrue(committed.await(1, TimeUnit.SECONDS));

            processor.onTagObserved(
                    observation("TAG-C", -40, STRONGER_OBSERVED_AT));
            awaitLane(execution.lane);

            TagProcessingMetrics.Snapshot snapshot = metrics.snapshot();
            assertEquals(3L, snapshot.observations());
            assertEquals(3L, snapshot.mapped());
            assertEquals(1L, snapshot.closedBursts());
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
                        "TAG-A", "N-001",
                        "TAG-B", "N-001",
                        "TAG-C", "N-001"),
                policy(8),
                clock,
                metrics,
                execution.lane);
        CountDownLatch registrationPublished =
                new CountDownLatch(1);
        processor.NEW_REGISTRATION
                .subscribe(
                        ignored -> registrationPublished.countDown());

        activate(processor);
        try {
            processor.onTagObserved(
                    observation("TAG-A", -60, OBSERVED_AT));
            awaitLane(execution.lane);

            clock.advanceNanos(100L);
            processor.onTagObserved(
                    observation("TAG-B", -50, STRONGER_OBSERVED_AT));
            awaitLane(execution.lane);
            assertTrue(
                    registrationPublished.await(
                            1,
                            TimeUnit.SECONDS));

            clock.advanceNanos(100L);
            processor.onTagObserved(
                    observation("TAG-C", -40, STRONGER_OBSERVED_AT));
            awaitLane(execution.lane);

            TagProcessingMetrics.Snapshot snapshot = metrics.snapshot();
            assertEquals(3L, snapshot.mapped());
            assertEquals(1L, snapshot.duplicates());
            assertEquals(1L, snapshot.closedBursts());
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
                        "TAG-A", "N-001",
                        "TAG-B", "N-001",
                        "TAG-C", "N-001"),
                configuration.timingNode(new NodeId("A")).tagProcessing(),
                clock,
                new TagProcessingMetrics(),
                execution.lane);
        connectToNode(processor, node);
        CountDownLatch committed = new CountDownLatch(1);
        node.logBook().newDataEntryEvent().subscribe(data -> {
            if (data instanceof AutomaticRegistration) {
                committed.countDown();
            }
        });

        activate(node);
        node.open(new LocationId(24));
        activate(processor);
        try {
            processor.onTagObserved(
                    observation("TAG-A", -50, OBSERVED_AT));
            awaitLane(execution.lane);

            clock.advanceNanos(50L);

            TagProcessingPolicy shorterQuietWindow =
                    new TagProcessingPolicy(
                            Duration.ofNanos(40L),
                            Duration.ofNanos(500L),
                            Duration.ofNanos(1000L),
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
    public void filterAndDuplicateHousekeepingUseSeparateScheduledRuns()
            throws Exception {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RecordingScheduledLane lane = new RecordingScheduledLane();
        TagProcessor processor = new TagProcessor(
                eventDataFor("TAG-A", "N-001"),
                policy(8),
                clock,
                new TagProcessingMetrics(),
                lane);

        activate(processor);
        try {
            processor.onTagObserved(observation("TAG-A", -50, OBSERVED_AT));
            awaitLane(lane);

            assertEquals(1, lane.scheduledOperations.size());

            clock.advanceNanos(100L);
            lane.scheduledOperations.get(0).run();

            assertEquals(2, lane.scheduledOperations.size());
        } finally {
            deactivate(processor);
            lane.closeWorker();
        }
    }

    @Test
    public void runtimeCadenceOverrideReplacesHousekeepingRegistration()
            throws Exception {
        TimingNode node = node(new RecordingStore());
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagExecution execution =
                newTagExecution("tp-tag-test");
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
                execution.lane);

        activate(processor);
        try {
            processor.onTagObserved(
                    observation("TAG-001", -42, OBSERVED_AT));
            awaitLane(execution.lane);

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
            awaitLane(execution.lane);

            /*
             * The override cancels the previous housekeeping registration and
             * the processor remains usable on the same serial lane. Periodic
             * scheduling/cancellation itself is verified by LaneSchedulerTest.
             */
            processor.onTagObserved(
                    observation("TAG-002", -41, OBSERVED_AT));
            awaitLane(execution.lane);
        } finally {
            deactivate(processor);
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
                execution.lane);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        activate(processor);
        try {
            execution.lane.execute(() -> {
                        blockerStarted.countDown();
                        await(releaseBlocker);
                    });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            processor.onTagObserved(
                    observation("TAG-001", -42, OBSERVED_AT));
            processor.onTagObserved(
                    observation("TAG-002", -41, OBSERVED_AT));

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
                execution.lane);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        activate(processor);
        execution.lane.execute(() -> {
                    blockerStarted.countDown();
                    await(releaseBlocker);
                });
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

        processor.onTagObserved(
                observation("TAG-001", -42, OBSERVED_AT));

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
                Duration.ofNanos(100L),
                Duration.ofNanos(500L),
                Duration.ofNanos(1000L),
                Duration.ofMillis(5L),
                queueCapacity);
    }

    private static TagObservation observation(
            String tagId,
            int rssi,
            TimingTimestamp observedAt) {
        return new TagObservation(
                new TagId(tagId),
                rssi,
                observedAt);
    }

    private static EventData referenceEventData() {
        return eventDataFor(
                "TAG-001", "N-001",
                "TAG-002", "N-002");
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
                                                registration.observedAt())));
    }

    private static void awaitLane(SerialLane lane)
            throws Exception {
        CountDownLatch barrier = new CountDownLatch(1);
        lane.execute(barrier::countDown);
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
        TimeSource timeSource = () -> RECORDED_AT.instant();
        return TimingNodeFixture.create(
                new NodeId("A"),
                store,
                new DefaultTimingDataFactory(),
                timeSource);
    }

    private static final class RecordingScheduledLane extends ScheduledSerialLane {
        private final ScheduledThreadPoolExecutor worker;
        private final List<Runnable> scheduledOperations = new ArrayList<Runnable>();

        private RecordingScheduledLane() {
            this(new ScheduledThreadPoolExecutor(1));
        }

        private RecordingScheduledLane(ScheduledThreadPoolExecutor worker) {
            super(8, 8, "recording-tag-lane", worker);
            this.worker = worker;
        }

        @Override
        public ScheduledRegistration scheduleWithFixedDelay(
                Runnable operation,
                long delayNanos) {
            scheduledOperations.add(operation);
            return () -> { };
        }

        private void closeWorker() {
            worker.shutdownNow();
        }
    }

    private static final class TagExecution {
        private final ScheduledSerialLane lane;

        private TagExecution(ScheduledSerialLane lane) {
            this.lane = lane;
        }
    }

    private static final class FakeMonotonicClock implements MonotonicClock {
        private volatile long now;

        @Override
        public long nowNanos() {
            return now;
        }

        private void advanceNanos(long nanos) {
            now += nanos;
        }
    }

    private static final class RecordingStore implements TimingDataPersistence {
        private final List<TimingData> appended = new ArrayList<>();

        @Override
        public LoadResult load() {
            return new LoadResult(new ArrayList<TimingData>(), false);
        }

        @Override
        public void append(TimingData data) {
            appended.add(data);
        }
    }
}
