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
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TagProcessorTest {
    private static final TimingTimestamp OBSERVED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    @Test
    public void selectedObservationUsesNormalTimingNodeCommitPath() throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();
        TagProcessingCounters counters = new TagProcessingCounters();
        TagProcessor processor = new TagProcessor(
                node,
                TagProcessorTest::mapReferenceTag,
                runtimePolicy(),
                SystemMonotonicClock.INSTANCE,
                scheduler,
                counters);
        SimulatedAntenna antenna = new SimulatedAntenna();
        CountDownLatch committed = new CountDownLatch(1);
        node.timingDataCommittedEvent().subscribe(
                data -> committed.countDown());

        node.start();
        node.invoke(TimingNodeCommands.open(new LocationId(24)));
        antenna.initialize();
        antenna.observations().subscribe(processor::onObservation);
        antenna.startInventory();
        try {
            antenna.emit(new TagId("TAG-001"), -42, OBSERVED_AT);

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
            antenna.close();
            processor.close();
            scheduler.shutdownNow();
            node.stop();
        }
    }

    @Test
    public void unmappedSelectedObservationNeverSubmits() throws Exception {
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();
        CountDownLatch mappingAttempted = new CountDownLatch(1);
        AtomicInteger submissions = new AtomicInteger();
        TagProcessingCounters counters = new TagProcessingCounters();
        TagProcessor processor = new TagProcessor(
                (registrationId, observedAt) -> {
                    submissions.incrementAndGet();
                    return CommandAdmission.ACCEPTED;
                },
                tagId -> {
                    mappingAttempted.countDown();
                    return null;
                },
                runtimePolicy(),
                SystemMonotonicClock.INSTANCE,
                scheduler,
                counters);
        try {
            processor.onObservation(
                    new TagObservation(
                            new TagId("TAG-UNKNOWN"),
                            -30,
                            OBSERVED_AT));

            assertTrue(mappingAttempted.await(2, TimeUnit.SECONDS));
            assertEquals(0, submissions.get());
        } finally {
            processor.close();
            scheduler.shutdownNow();
        }
    }

    private static TagProcessingPolicy runtimePolicy() {
        return new TagProcessingPolicy(
                Duration.ofMillis(10),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofMillis(2));
    }

    private static RegistrationId mapReferenceTag(TagId tagId) {
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
