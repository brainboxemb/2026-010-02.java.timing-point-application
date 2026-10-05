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
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.DecryptedTagId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TagProcessorTest {
    private static final TimingTimestamp OBSERVED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp STRONGER_OBSERVED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:00.050000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    @Test
    public void eventCallbackOnlyQueuesAndMappingRunsOnExecutionLane()
            throws Exception {
        TimingNode node = node(new RecordingStore());
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("tp-tag-test");
        AtomicReference<String> mapperThread = new AtomicReference<>();
        TagProcessor processor = new TagProcessor(
                node,
                tagId -> {
                    mapperThread.set(Thread.currentThread().getName());
                    return null;
                },
                policy(8),
                clock,
                new TagProcessingMetrics(),
                executor);

        processor.start();
        try {
            String callbackThread = Thread.currentThread().getName();
            processor.onObservation(observation("TAG-001", -42, OBSERVED_AT));
            awaitLane(executor);

            assertEquals("tp-tag-test", mapperThread.get());
            assertFalse(callbackThread.equals(mapperThread.get()));
        } finally {
            processor.stop();
        }
    }

    @Test
    public void differentTagsForSameRegistrationShareOneScheduledPassage()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                node,
                tagId -> new RegistrationId("N-001"),
                policy(8),
                clock,
                metrics,
                executor);
        CountDownLatch committed = new CountDownLatch(1);
        node.timingDataCommittedEvent().subscribe(data -> committed.countDown());

        node.start();
        node.invoke(TimingNodeCommands.open(new LocationId(24)));
        processor.start();
        try {
            processor.onObservation(
                    observation("TAG-A", -60, OBSERVED_AT));
            processor.onObservation(
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
            processor.stop();
            node.stop();
        }
    }

    @Test
    public void acceptedRegistrationSuppressesLaterMappedObservationsBeforePassageFiltering()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                node,
                tagId -> new RegistrationId("N-001"),
                policy(8),
                clock,
                metrics,
                executor);
        CountDownLatch committed = new CountDownLatch(1);
        node.timingDataCommittedEvent().subscribe(data -> committed.countDown());

        node.start();
        node.invoke(TimingNodeCommands.open(new LocationId(24)));
        processor.start();
        try {
            processor.onObservation(
                    observation("TAG-A", -60, OBSERVED_AT));
            awaitLane(executor);

            /*
             * This closes the first passage. TagObservationFilter has already
             * created a new burst for TAG-B before the callback performs the
             * TimingNode offer; ACCEPTED must therefore start duplicate
             * suppression and discard that just-created next burst.
             */
            clock.advanceNanos(100L);
            processor.onObservation(
                    observation("TAG-B", -50, STRONGER_OBSERVED_AT));
            awaitLane(executor);
            assertTrue(committed.await(1, TimeUnit.SECONDS));

            processor.onObservation(
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
            processor.stop();
            node.stop();
        }
    }

    @Test
    public void rejectedTimingNodeOfferDoesNotStartDuplicateSuppression()
            throws Exception {
        TimingNode node = node(new RecordingStore());
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                node,
                tagId -> new RegistrationId("N-001"),
                policy(8),
                clock,
                metrics,
                executor);

        // TimingNode deliberately remains NEW so every offer is NOT_RUNNING.
        processor.start();
        try {
            processor.onObservation(
                    observation("TAG-A", -60, OBSERVED_AT));
            awaitLane(executor);

            clock.advanceNanos(100L);
            processor.onObservation(
                    observation("TAG-B", -50, STRONGER_OBSERVED_AT));
            awaitLane(executor);

            clock.advanceNanos(100L);
            processor.onObservation(
                    observation("TAG-C", -40, STRONGER_OBSERVED_AT));
            awaitLane(executor);

            TagProcessingMetrics.Snapshot snapshot = metrics.snapshot();
            assertEquals(3L, snapshot.mapped());
            assertEquals(0L, snapshot.duplicates());
            assertEquals(2L, snapshot.closedBursts());
            assertEquals(2L, snapshot.nodeNotRunning());
        } finally {
            processor.stop();
        }
    }

    @Test
    public void runtimePolicyOverrideReevaluatesExistingPassage()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("tp-tag-test");
        TagProcessingPolicy startup = policy(8);
        ApplicationConfiguration configuration =
                ApplicationConfiguration.singleTimingNode(
                        new NodeId("TN-01"),
                        startup);
        TagProcessor processor = new TagProcessor(
                node,
                tagId -> new RegistrationId("N-001"),
                configuration.timingNode(new NodeId("TN-01")).tagProcessing(),
                clock,
                new TagProcessingMetrics(),
                executor);
        CountDownLatch committed = new CountDownLatch(1);
        node.timingDataCommittedEvent().subscribe(data -> committed.countDown());

        node.start();
        node.invoke(TimingNodeCommands.open(new LocationId(24)));
        processor.start();
        try {
            processor.onObservation(
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
            processor.stop();
            node.stop();
        }
    }

    @Test
    public void runtimeCadenceOverrideReplacesHousekeepingRegistration()
            throws Exception {
        TimingNode node = node(new RecordingStore());
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("tp-tag-test");
        TagProcessingPolicy startup = policy(8);
        ApplicationConfiguration configuration =
                ApplicationConfiguration.singleTimingNode(
                        new NodeId("TN-01"),
                        startup);
        TagProcessor processor = new TagProcessor(
                node,
                TagProcessorTest::mapReferenceTag,
                configuration.timingNode(new NodeId("TN-01")).tagProcessing(),
                clock,
                new TagProcessingMetrics(),
                executor);

        processor.start();
        try {
            processor.onObservation(
                    observation("TAG-001", -42, OBSERVED_AT));
            awaitLane(executor);

            SerialScheduledExecutor.Metrics.Snapshot before =
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

            SerialScheduledExecutor.Metrics.Snapshot after =
                    executor.metrics().snapshot();
            assertTrue(after.scheduledRegistrationCount() >= 2L);
            assertTrue(after.scheduledCancellationCount() >= 1L);
        } finally {
            processor.stop();
        }
    }

    @Test
    public void boundedObservationQueueReportsOverload() throws Exception {
        TimingNode node = node(new RecordingStore());
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                node,
                TagProcessorTest::mapReferenceTag,
                policy(1),
                clock,
                metrics,
                executor);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        processor.start();
        try {
            assertTrue(executor.execute(() -> {
                blockerStarted.countDown();
                await(releaseBlocker);
            }));
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            processor.onObservation(
                    observation("TAG-001", -42, OBSERVED_AT));
            processor.onObservation(
                    observation("TAG-002", -41, OBSERVED_AT));

            assertEquals(
                    1L,
                    metrics.snapshot().observationQueueFull());
        } finally {
            releaseBlocker.countDown();
            processor.stop();
        }
    }

    @Test
    public void stopDrainsAcceptedInputWithoutForceClosingPassage()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("tp-tag-test");
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagProcessor processor = new TagProcessor(
                node,
                TagProcessorTest::mapReferenceTag,
                policy(4),
                clock,
                metrics,
                executor);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        processor.start();
        assertTrue(executor.execute(() -> {
            blockerStarted.countDown();
            await(releaseBlocker);
        }));
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

        processor.onObservation(
                observation("TAG-001", -42, OBSERVED_AT));

        Thread stopper = new Thread(processor::stop);
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
                new DecryptedTagId(tagId),
                rssi,
                observedAt);
    }

    private static RegistrationId mapReferenceTag(DecryptedTagId tagId) {
        String value = tagId.value();
        if (!value.startsWith("TAG-") || value.length() == 4) {
            return null;
        }
        return new RegistrationId("N-" + value.substring(4));
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

    private static TimingNode node(RecordingStore store) {
        TimeSource timeSource = () -> RECORDED_AT;
        return new TimingNode(
                new NodeId("TN-01"),
                store,
                new DefaultTimingDataFactory(),
                timeSource,
                ReadOnlyConfiguration.fixed(
                        TagProcessingPolicy.defaults()),
                tagId -> null,
                new SerialExecutor(
                        32,
                        "tag-processor-test-node"),
                new SerialScheduledExecutor(
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
