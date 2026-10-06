package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

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
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutorMetrics;

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

    private SerialExecutor newSerialExecutor(
            int capacity,
            String threadName) {
        ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        runnable -> new Thread(runnable, threadName));
        ownedWorkers.add(worker);
        return new SerialExecutor(capacity, threadName, worker);
    }

    private SerialScheduledExecutor newScheduledExecutor(
            String threadName) {
        ScheduledThreadPoolExecutor worker =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> new Thread(runnable, threadName));
        worker.setRemoveOnCancelPolicy(true);
        ownedWorkers.add(worker);
        return new SerialScheduledExecutor(
                32,
                threadName,
                worker);
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
        SerialScheduledExecutor executor =
                newScheduledExecutor("tp-tag-test");
        TagProcessingMetrics metrics =
                new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                node,
                EventData.empty(),
                policy(8),
                clock,
                metrics,
                executor);

        processor.activate();
        try {
            processor.onTagObserved(
                    observation("TAG-001", -42, OBSERVED_AT));
            awaitLane(executor);

            assertEquals(
                    1L,
                    metrics.snapshot().unmapped());
        } finally {
            processor.deactivate();
        }
    }

    @Test
    public void differentTagsForSameRegistrationShareOneScheduledPassage()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                newScheduledExecutor("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                node,
                eventDataFor(
                        "TAG-A", "N-001",
                        "TAG-B", "N-001",
                        "TAG-C", "N-001"),
                policy(8),
                clock,
                metrics,
                executor);
        CountDownLatch committed = new CountDownLatch(1);
        node.timingDataCommittedEvent().subscribe(data -> committed.countDown());

        node.activate();
        node.invoke(TimingNodeCommands.open(new LocationId(24)));
        processor.activate();
        try {
            processor.onTagObserved(
                    observation("TAG-A", -60, OBSERVED_AT));
            processor.onTagObserved(
                    observation("TAG-B", -30, STRONGER_OBSERVED_AT));
            awaitLane(executor);

            clock.advanceNanos(100L);

            assertTrue(committed.await(1, TimeUnit.SECONDS));
            awaitLane(executor);
            assertEquals(1, store.appended.size());
            AutomaticRegistration registration =
                    (AutomaticRegistration) store.appended.get(0);
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
            assertEquals(1L, snapshot.admitted());
        } finally {
            processor.deactivate();
            node.deactivate();
        }
    }

    @Test
    public void acceptedRegistrationSuppressesLaterMappedObservationsBeforePassageFiltering()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                newScheduledExecutor("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                node,
                eventDataFor(
                        "TAG-A", "N-001",
                        "TAG-B", "N-001",
                        "TAG-C", "N-001"),
                policy(8),
                clock,
                metrics,
                executor);
        CountDownLatch committed = new CountDownLatch(1);
        node.timingDataCommittedEvent().subscribe(data -> committed.countDown());

        node.activate();
        node.invoke(TimingNodeCommands.open(new LocationId(24)));
        processor.activate();
        try {
            processor.onTagObserved(
                    observation("TAG-A", -60, OBSERVED_AT));
            awaitLane(executor);

            /*
             * This closes the first passage. TagObservationFilter has already
             * created a new burst for TAG-B before the callback performs the
             * TimingNode offer; ACCEPTED must therefore start duplicate
             * suppression and discard that just-created next burst.
             */
            clock.advanceNanos(100L);
            processor.onTagObserved(
                    observation("TAG-B", -50, STRONGER_OBSERVED_AT));
            awaitLane(executor);
            assertTrue(committed.await(1, TimeUnit.SECONDS));

            processor.onTagObserved(
                    observation("TAG-C", -40, STRONGER_OBSERVED_AT));
            awaitLane(executor);

            TagProcessingMetrics.Snapshot snapshot = metrics.snapshot();
            assertEquals(3L, snapshot.observations());
            assertEquals(3L, snapshot.mapped());
            assertEquals(1L, snapshot.closedBursts());
            assertEquals(1L, snapshot.admitted());
            assertEquals(1L, snapshot.duplicates());
            assertEquals(1, store.appended.size());
        } finally {
            processor.deactivate();
            node.deactivate();
        }
    }

    @Test
    public void rejectedTimingNodeOfferDoesNotStartDuplicateSuppression()
            throws Exception {
        TimingNode node = node(new RecordingStore());
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                newScheduledExecutor("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                node,
                eventDataFor(
                        "TAG-A", "N-001",
                        "TAG-B", "N-001",
                        "TAG-C", "N-001"),
                policy(8),
                clock,
                metrics,
                executor);

        // TimingNode deliberately remains NEW so every offer is NOT_RUNNING.
        processor.activate();
        try {
            processor.onTagObserved(
                    observation("TAG-A", -60, OBSERVED_AT));
            awaitLane(executor);

            clock.advanceNanos(100L);
            processor.onTagObserved(
                    observation("TAG-B", -50, STRONGER_OBSERVED_AT));
            awaitLane(executor);

            clock.advanceNanos(100L);
            processor.onTagObserved(
                    observation("TAG-C", -40, STRONGER_OBSERVED_AT));
            awaitLane(executor);

            TagProcessingMetrics.Snapshot snapshot = metrics.snapshot();
            assertEquals(3L, snapshot.mapped());
            assertEquals(0L, snapshot.duplicates());
            assertEquals(2L, snapshot.closedBursts());
            assertEquals(2L, snapshot.nodeNotRunning());
        } finally {
            processor.deactivate();
        }
    }

    @Test
    public void runtimePolicyOverrideReevaluatesExistingPassage()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                newScheduledExecutor("tp-tag-test");
        TagProcessingPolicy startup = policy(8);
        ApplicationConfiguration configuration =
                ApplicationConfiguration.singleTimingNode(
                        new NodeId("TN-01"),
                        startup);
        TagProcessor processor = new TagProcessor(
                node,
                eventDataFor(
                        "TAG-A", "N-001",
                        "TAG-B", "N-001",
                        "TAG-C", "N-001"),
                configuration.timingNode(new NodeId("TN-01")).tagProcessing(),
                clock,
                new TagProcessingMetrics(),
                executor);
        CountDownLatch committed = new CountDownLatch(1);
        node.timingDataCommittedEvent().subscribe(data -> committed.countDown());

        node.activate();
        node.invoke(TimingNodeCommands.open(new LocationId(24)));
        processor.activate();
        try {
            processor.onTagObserved(
                    observation("TAG-A", -50, OBSERVED_AT));
            awaitLane(executor);

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
                            .timingNode(new NodeId("TN-01"))
                            .tagProcessing()
                            .override(shorterQuietWindow));

            assertTrue(committed.await(1, TimeUnit.SECONDS));
            assertEquals(1, store.appended.size());
        } finally {
            processor.deactivate();
            node.deactivate();
        }
    }

    @Test
    public void runtimeCadenceOverrideReplacesHousekeepingRegistration()
            throws Exception {
        TimingNode node = node(new RecordingStore());
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                newScheduledExecutor("tp-tag-test");
        TagProcessingPolicy startup = policy(8);
        ApplicationConfiguration configuration =
                ApplicationConfiguration.singleTimingNode(
                        new NodeId("TN-01"),
                        startup);
        TagProcessor processor = new TagProcessor(
                node,
                referenceEventData(),
                configuration.timingNode(new NodeId("TN-01")).tagProcessing(),
                clock,
                new TagProcessingMetrics(),
                executor);

        processor.activate();
        try {
            processor.onTagObserved(
                    observation("TAG-001", -42, OBSERVED_AT));
            awaitLane(executor);

            SerialScheduledExecutorMetrics.Snapshot before =
                    executor.metrics().snapshot();
            assertEquals(1L, before.scheduledRegistrationCount());

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
                            .timingNode(new NodeId("TN-01"))
                            .tagProcessing()
                            .override(fasterSweep));
            awaitLane(executor);

            SerialScheduledExecutorMetrics.Snapshot after =
                    executor.metrics().snapshot();
            assertTrue(after.scheduledRegistrationCount() >= 2L);
            assertTrue(after.scheduledCancellationCount() >= 1L);
        } finally {
            processor.deactivate();
        }
    }

    @Test
    public void boundedObservationQueueReportsOverload() throws Exception {
        TimingNode node = node(new RecordingStore());
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                newScheduledExecutor("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                node,
                referenceEventData(),
                policy(1),
                clock,
                metrics,
                executor);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        processor.activate();
        try {
            assertTrue(executor.execute(() -> {
                blockerStarted.countDown();
                await(releaseBlocker);
            }));
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
            processor.deactivate();
        }
    }

    @Test
    public void stopDrainsAcceptedInputWithoutForceClosingPassage()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                newScheduledExecutor("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                node,
                referenceEventData(),
                policy(4),
                clock,
                metrics,
                executor);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        processor.activate();
        assertTrue(executor.execute(() -> {
            blockerStarted.countDown();
            await(releaseBlocker);
        }));
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

        processor.onTagObserved(
                observation("TAG-001", -42, OBSERVED_AT));

        Thread stopper = new Thread(processor::deactivate);
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

    private static void awaitLane(SerialScheduledExecutor executor)
            throws Exception {
        CountDownLatch barrier = new CountDownLatch(1);
        assertTrue(executor.execute(barrier::countDown));
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
        TimeSource timeSource = () -> RECORDED_AT;
        return new TimingNode(
                new NodeId("TN-01"),
                store,
                new DefaultTimingDataFactory(),
                timeSource,
                ReadOnlyConfiguration.fixed(
                        TagProcessingPolicy.defaults()),
                EventData.empty(),
                newSerialExecutor(32, "tag-processor-test-node"),
                newScheduledExecutor(
                        "tag-processor-test-owned-tag"));
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
