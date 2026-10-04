package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.AutomaticRegistration;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TagProcessorTest {
    private static final TimingTimestamp OBSERVED_1 =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp OBSERVED_2 =
            TimingTimestamp.parse("2026-10-01T12:00:00.100000000Z");
    private static final TimingTimestamp OBSERVED_3 =
            TimingTimestamp.parse("2026-10-01T12:00:00.200000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    @Test
    public void strongestObservationSuppliesRegistrationTimeAndEqualRssiKeepsEarlierRead() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TagProcessor processor =
                new TagProcessor(
                        node,
                        TagProcessorTest::mapReferenceTag,
                        manualPolicy(),
                        clock,
                        scheduler);
        SimulatedAntenna antenna = new SimulatedAntenna();

        node.start();
        antenna.initialize();
        antenna.observations().subscribe(processor::onObservation);
        antenna.startInventory();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            antenna.emit(new TagId("TAG-001"), -60, OBSERVED_1);
            clock.advanceNanos(10L);
            antenna.emit(new TagId("TAG-001"), -40, OBSERVED_2);
            clock.advanceNanos(10L);
            antenna.emit(new TagId("TAG-001"), -40, OBSERVED_3);

            clock.advanceNanos(100L);
            processor.expireBursts();

            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertEquals(1, store.appended.size());

            AutomaticRegistration registration =
                    (AutomaticRegistration) store.appended.get(0);
            assertEquals(new RegistrationId("N-001"), registration.registrationId());
            assertEquals(OBSERVED_2, registration.effectiveTime());
            assertEquals(RECORDED_AT, registration.recordedAt());
            assertEquals(3L, processor.observationCount());
            assertEquals(1L, processor.closedBurstCount());
            assertEquals(1L, processor.mappedCount());
            assertEquals(1L, processor.admittedCount());
        } finally {
            antenna.close();
            processor.close();
            scheduler.shutdownNow();
            node.stop();
        }
    }

    @Test
    public void maximumDurationClosesContinuouslyVisibleTagBeforeQuietTimeout() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TagProcessingPolicy policy = new TagProcessingPolicy(
                Duration.ofNanos(100L),
                Duration.ofNanos(150L),
                Duration.ofNanos(1000L),
                Duration.ofDays(1));
        TagProcessor processor = new TagProcessor(
                node,
                TagProcessorTest::mapReferenceTag,
                policy,
                clock,
                scheduler);

        node.start();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            processor.onObservation(observation("TAG-001", -60, OBSERVED_1));
            clock.advanceNanos(90L);
            processor.onObservation(observation("TAG-001", -40, OBSERVED_2));
            clock.advanceNanos(60L);

            processor.expireBursts();

            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());
            AutomaticRegistration registration =
                    (AutomaticRegistration) store.appended.get(0);
            assertEquals(OBSERVED_2, registration.effectiveTime());
        } finally {
            processor.close();
            scheduler.shutdownNow();
            node.stop();
        }
    }

    @Test
    public void quietTimeoutClosesBurstWithoutAnotherObservation() throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TagProcessingPolicy policy = new TagProcessingPolicy(
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofMillis(2));
        TagProcessor processor = new TagProcessor(
                node,
                TagProcessorTest::mapReferenceTag,
                policy,
                SystemMonotonicClock.INSTANCE,
                scheduler);
        CountDownLatch committed = new CountDownLatch(1);
        node.timingDataCommittedEvent().subscribe(data -> committed.countDown());

        node.start();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));
            processor.onObservation(observation("TAG-001", -50, OBSERVED_1));

            assertTrue(
                    "registration was not committed after quiet timeout",
                    committed.await(2, TimeUnit.SECONDS));
            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());
        } finally {
            processor.close();
            scheduler.shutdownNow();
            node.stop();
        }
    }

    @Test
    public void duplicateWindowUsesRegistrationIdAcrossDifferentTags() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TagRegistrationMapper mapper = tagId -> {
            if ("TAG-A".equals(tagId.value()) || "TAG-B".equals(tagId.value())) {
                return new RegistrationId("N-001");
            }
            return null;
        };
        TagProcessor processor =
                new TagProcessor(node, mapper, manualPolicy(), clock, scheduler);

        node.start();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            processor.onObservation(observation("TAG-A", -50, OBSERVED_1));
            clock.advanceNanos(100L);
            processor.expireBursts();
            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());

            clock.advanceNanos(10L);
            processor.onObservation(observation("TAG-B", -45, OBSERVED_2));
            clock.advanceNanos(100L);
            processor.expireBursts();

            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertEquals(1L, processor.duplicateCount());
        } finally {
            processor.close();
            scheduler.shutdownNow();
            node.stop();
        }
    }

    @Test
    public void notRunningAdmissionDoesNotStartDuplicateWindow() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TagProcessor processor =
                new TagProcessor(
                        node,
                        TagProcessorTest::mapReferenceTag,
                        manualPolicy(),
                        clock,
                        scheduler);

        try {
            processor.onObservation(observation("TAG-001", -50, OBSERVED_1));
            clock.advanceNanos(100L);
            processor.expireBursts();
            assertEquals(1L, processor.nodeNotRunningCount());

            node.start();
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            clock.advanceNanos(10L);
            processor.onObservation(observation("TAG-001", -45, OBSERVED_2));
            clock.advanceNanos(100L);
            processor.expireBursts();

            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertEquals(1L, processor.admittedCount());
            assertEquals(0L, processor.duplicateCount());
        } finally {
            processor.close();
            scheduler.shutdownNow();
            node.stop();
        }
    }

    @Test
    public void unmappedTagDoesNotReachTimingNode() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        TagProcessor processor =
                new TagProcessor(node, tagId -> null, manualPolicy(), clock, scheduler);

        node.start();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));
            processor.onObservation(observation("TAG-UNKNOWN", -30, OBSERVED_1));
            clock.advanceNanos(100L);
            processor.expireBursts();

            assertEquals(0, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertTrue(store.appended.isEmpty());
            assertEquals(1L, processor.unmappedCount());
            assertEquals(0L, processor.admittedCount());
        } finally {
            processor.close();
            scheduler.shutdownNow();
            node.stop();
        }
    }

    private static TagObservation observation(
            String tagId,
            int rssi,
            TimingTimestamp observedAt) {
        return new TagObservation(new TagId(tagId), rssi, observedAt);
    }

    private static RegistrationId mapReferenceTag(TagId tagId) {
        String value = tagId.value();
        if (!value.startsWith("TAG-") || value.length() == 4) {
            return null;
        }
        return new RegistrationId("N-" + value.substring(4));
    }

    private static TagProcessingPolicy manualPolicy() {
        return new TagProcessingPolicy(
                Duration.ofNanos(100L),
                Duration.ofNanos(500L),
                Duration.ofNanos(1000L),
                Duration.ofDays(1));
    }

    private static TimingNode node(RecordingStore store) {
        TimeSource timeSource = () -> RECORDED_AT;
        return new TimingNode(
                new NodeId("TN-01"),
                store,
                new DefaultTimingDataFactory(),
                timeSource);
    }

    private static final class FakeMonotonicClock implements MonotonicClock {
        private long now;

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
