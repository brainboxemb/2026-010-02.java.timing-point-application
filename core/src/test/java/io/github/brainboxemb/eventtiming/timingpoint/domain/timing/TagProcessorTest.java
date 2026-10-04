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
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.SimulatedAntenna;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TagProcessorTest {
    private static final TimingTimestamp OBSERVED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    @Test
    public void simulatedKnownTagUsesNormalTimingNodeCommitPath() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        Map<TagId, RegistrationId> references = new HashMap<>();
        references.put(new TagId("TAG-001"), new RegistrationId("N0001"));
        TagProcessor processor =
                new TagProcessor(node, new MapTagRegistrationResolver(references));
        SimulatedAntenna antenna = new SimulatedAntenna();

        node.start();
        antenna.start(processor::onObservation);
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            antenna.emit("TAG-001", OBSERVED_AT);

            // This ordered query runs after the admitted registration command.
            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertEquals(1, store.appended.size());

            TimingData data = store.appended.get(0);
            assertTrue(data instanceof AutomaticRegistration);
            AutomaticRegistration registration = (AutomaticRegistration) data;
            assertEquals(new NodeId("TN-01"), registration.timingNodeId());
            assertEquals(1L, registration.sequenceNumber());
            assertEquals(new LocationId(24), registration.locationId());
            assertEquals(new RegistrationId("N0001"), registration.registrationId());
            assertEquals(OBSERVED_AT, registration.effectiveTime());
            assertEquals(RECORDED_AT, registration.recordedAt());
        } finally {
            antenna.close();
            node.stop();
        }
    }

    @Test
    public void unknownTagIsFilteredBeforeTimingNodeCommit() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        TagProcessor processor =
                new TagProcessor(node, new MapTagRegistrationResolver(
                        new HashMap<TagId, RegistrationId>()));
        SimulatedAntenna antenna = new SimulatedAntenna();

        node.start();
        antenna.start(processor::onObservation);
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            antenna.emit("TAG-UNKNOWN", OBSERVED_AT);

            assertEquals(0, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertTrue(store.appended.isEmpty());
        } finally {
            antenna.close();
            node.stop();
        }
    }

    @Test
    public void admissionIsNotTheLaterDomainCommitResult() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        Map<TagId, RegistrationId> references = new HashMap<>();
        references.put(new TagId("TAG-001"), new RegistrationId("N0001"));
        TagProcessor processor =
                new TagProcessor(node, new MapTagRegistrationResolver(references));

        node.start();
        try {
            assertEquals(
                    TagProcessor.ObservationResult.ADMITTED,
                    processor.process(
                            new Antenna.Observation("TAG-001", OBSERVED_AT)));

            // The command was admitted while the node was CLOSED, so the later
            // domain operation rejects it without creating committed TimingData.
            assertEquals(0, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertTrue(store.appended.isEmpty());
        } finally {
            node.stop();
        }
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
