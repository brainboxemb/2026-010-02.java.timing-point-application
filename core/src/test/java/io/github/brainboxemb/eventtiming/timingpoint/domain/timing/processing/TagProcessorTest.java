package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.AutomaticRegistration;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.DecryptedTagId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.PeriodicExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.PeriodicTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TagProcessorTest {
    private static final TimingTimestamp OBSERVED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp STRONGER_OBSERVED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:00.050000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    @Test
    public void selectedObservationUsesNormalTimingNodeCommitPath()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        ManualPeriodicExecutor periodicExecutor = new ManualPeriodicExecutor();
        TagProcessor processor = new TagProcessor(
                node,
                TagProcessorTest::mapReferenceTag,
                manualPolicy(),
                clock,
                new TagProcessingCounters(),
                periodicExecutor);
        SimulatedAntenna antenna = new SimulatedAntenna();
        Consumer<TagObservation> listener = processor::onObservation;
        CountDownLatch committed = new CountDownLatch(1);
        node.timingDataCommittedEvent().subscribe(
                data -> committed.countDown());

        node.start();
        node.invoke(TimingNodeCommands.open(new LocationId(24)));
        processor.start();
        antenna.initialize();
        antenna.observations().subscribe(listener);
        antenna.startInventory();
        try {
            antenna.emit(
                    new DecryptedTagId("TAG-001"),
                    -42,
                    OBSERVED_AT);
            clock.advanceNanos(100L);
            periodicExecutor.runOnce();

            assertTrue(
                    "selected tag was not committed",
                    committed.await(2, TimeUnit.SECONDS));

            assertEquals(1, store.appended.size());
            AutomaticRegistration registration =
                    (AutomaticRegistration) store.appended.get(0);
            assertEquals(
                    new RegistrationId("N-001"),
                    registration.registrationId());
            assertEquals(OBSERVED_AT, registration.effectiveTime());
        } finally {
            antenna.stopInventory();
            antenna.observations().unsubscribe(listener);
            processor.stop();
            antenna.close();
            node.stop();
        }
    }

    @Test
    public void differentTagsForSameRegistrationShareOnePassage()
            throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        ManualPeriodicExecutor periodicExecutor = new ManualPeriodicExecutor();
        TagProcessingCounters counters = new TagProcessingCounters();
        TagProcessor processor = new TagProcessor(
                node,
                tagId -> new RegistrationId("N-001"),
                manualPolicy(),
                clock,
                counters,
                periodicExecutor);
        CountDownLatch committed = new CountDownLatch(1);
        node.timingDataCommittedEvent().subscribe(
                data -> committed.countDown());

        node.start();
        node.invoke(TimingNodeCommands.open(new LocationId(24)));
        processor.start();
        try {
            processor.onObservation(new TagObservation(
                    new DecryptedTagId("TAG-A"),
                    -60,
                    OBSERVED_AT));
            clock.advanceNanos(10L);
            processor.onObservation(new TagObservation(
                    new DecryptedTagId("TAG-B"),
                    -30,
                    STRONGER_OBSERVED_AT));
            clock.advanceNanos(100L);
            periodicExecutor.runOnce();

            assertTrue(
                    "shared registration passage was not committed",
                    committed.await(2, TimeUnit.SECONDS));

            assertEquals(1, store.appended.size());
            AutomaticRegistration registration =
                    (AutomaticRegistration) store.appended.get(0);
            assertEquals(
                    new RegistrationId("N-001"),
                    registration.registrationId());
            assertEquals(
                    STRONGER_OBSERVED_AT,
                    registration.effectiveTime());

            TagProcessingCounters.Snapshot snapshot = counters.snapshot();
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
    public void unmappedObservationNeverOpensPassage() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        FakeMonotonicClock clock = new FakeMonotonicClock();
        ManualPeriodicExecutor periodicExecutor = new ManualPeriodicExecutor();
        TagProcessingCounters counters = new TagProcessingCounters();
        TagProcessor processor = new TagProcessor(
                node,
                tagId -> null,
                manualPolicy(),
                clock,
                counters,
                periodicExecutor);
        processor.start();

        processor.onObservation(
                new TagObservation(
                        new DecryptedTagId("TAG-UNKNOWN"),
                        -30,
                        OBSERVED_AT));

        TagProcessingCounters.Snapshot snapshot = counters.snapshot();
        assertEquals(1L, snapshot.observations());
        assertEquals(1L, snapshot.unmapped());
        assertEquals(0L, snapshot.closedBursts());
        assertTrue(store.appended.isEmpty());
        processor.stop();
    }

    private static TagProcessingPolicy manualPolicy() {
        return new TagProcessingPolicy(
                Duration.ofNanos(100L),
                Duration.ofNanos(500L),
                Duration.ofNanos(1000L),
                Duration.ofDays(1));
    }

    private static RegistrationId mapReferenceTag(DecryptedTagId tagId) {
        String value = tagId.value();
        if (!value.startsWith("TAG-") || value.length() == 4) {
            return null;
        }
        return new RegistrationId("N-" + value.substring(4));
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

    private static final class ManualPeriodicExecutor
            implements PeriodicExecutor {
        private Runnable task;
        private boolean active;

        @Override
        public PeriodicTask scheduleWithFixedDelay(
                Runnable task,
                long delayNanos) {
            if (task == null) {
                throw new IllegalArgumentException("task must not be null");
            }
            if (delayNanos < 1L) {
                throw new IllegalArgumentException(
                        "delayNanos must be positive");
            }
            if (active) {
                throw new IllegalStateException(
                        "periodic task is already active");
            }
            this.task = task;
            active = true;
            return () -> active = false;
        }

        private void runOnce() {
            if (!active) {
                throw new IllegalStateException(
                        "periodic task is not active");
            }
            task.run();
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
