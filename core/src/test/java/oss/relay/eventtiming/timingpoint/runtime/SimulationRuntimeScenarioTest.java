package oss.relay.eventtiming.timingpoint.runtime;

import oss.relay.eventtiming.eventdata.EventData;
import oss.relay.eventtiming.eventdata.TagId;
import oss.relay.eventtiming.eventdata.defaultprofile.DefaultEventDataProvider;
import oss.relay.eventtiming.timingdata.TimingRecord;
import oss.relay.eventtiming.timingdata.TimingDataTypes.LocationId;
import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.eventtiming.timingpoint.application.SimulationControl;
import oss.relay.eventtiming.timingpoint.application.TimingNodeProxy;
import oss.relay.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import oss.relay.eventtiming.timingpoint.infra.BuildIdentity;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import oss.relay.eventtiming.timingpoint.runtime.config.Config;
import oss.relay.eventtiming.timingpoint.runtime.simulation.SimulationRuntime;
import oss.relay.eventtiming.timingpoint.runtime.config.Presentation;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SimulationRuntimeScenarioTest {
    private static final RegistrationId REGISTRATION =
            new RegistrationId("A0042");
    private static final TagId TAG_A =
            new TagId("A0042A");
    private static final TagId TAG_B =
            new TagId("A0042B");

    @Rule
    public final TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    @Test
    public void simulatedProfileReachesRegistrationOnlyThroughAntennaAndTagProcessor()
            throws Exception {
        SimulatedAntenna antenna =
                new SimulatedAntenna();
        TimingApplicationRuntime application =
                SimulationRuntime.create(
                        identity(),
                        config(),
                        antenna,
                        eventData());

        List<TagObservation> observed =
                Collections.synchronizedList(
                        new ArrayList<TagObservation>());
        CountDownLatch fourObservations =
                new CountDownLatch(4);
        antenna.tagObservedEvent()
                .subscribe(
                        observation -> {
                            observed.add(
                                    observation);
                            fourObservations.countDown();
                        });

        AtomicReference<TimingRecord> committed =
                new AtomicReference<TimingRecord>();
        CountDownLatch registrationCommitted =
                new CountDownLatch(1);
        application
                .presentationGateway()
                .timingNode(new NodeId("A"))
                .NEW_DATA_ENTRY
                .subscribe(
                        data -> {
                            if (data.kind()
                                    == TimingRecord.Kind.AUTOMATIC_REGISTRATION) {
                                committed.set(data);
                                registrationCommitted.countDown();
                            }
                        });

        application.activate();
        try {
            await(
                    application.antennaManager()::isReady,
                    2000L);

            TimingNodeProxy node =
                    application
                            .presentationGateway()
                            .timingNode(new NodeId("A"));
            node.open(
                    new LocationId(24));
            await(
                    antenna::inventoryRunning,
                    2000L);

            assertTrue(
                    application
                            .presentationGateway()
                            .capabilities()
                            .tagScenarioSimulationEnabled());

            assertEquals(
                    SimulationControl.StartResult.ACCEPTED,
                    application
                            .presentationGateway()
                            .simulation()
                            .startRegistration(
                                    REGISTRATION,
                                    "normal"));

            assertTrue(
                    fourObservations.await(
                            2L,
                            TimeUnit.SECONDS));
            assertTrue(
                    registrationCommitted.await(
                            2L,
                            TimeUnit.SECONDS));

            TimingRecord registration = committed.get();
            assertEquals(
                    REGISTRATION.packedValue(),
                    registration.registrationId());
            assertEquals(
                    4,
                    observed.size());

            TagObservation strongest =
                    observed.get(2);
            assertEquals(
                    TAG_A,
                    strongest.tagId());
            assertEquals(
                    -42,
                    strongest.rssi());
            Instant strongestInstant =
                    Instant.ofEpochMilli(strongest.observedAtEpochMillis());
            long expectedNanos =
                    (strongestInstant.getNano()
                            / 10_000_000L)
                            * 10_000_000L;
            assertEquals(
                    Instant.ofEpochSecond(
                            strongestInstant.getEpochSecond(),
                            expectedNanos),
                    Instant.ofEpochMilli(
                            registration.effectiveTimeMillis()));

            List<TimingRecord> history =
                    new ArrayList<TimingRecord>();
            assertEquals(
                    2,
                    node.visitLogBookFrom(
                            1L,
                            10,
                            history::add));
            assertEquals(
                    2,
                    history.size());
            assertEquals(
                    TimingRecord.Kind.AUTOMATIC_REGISTRATION,
                    history.get(1).kind());
        } finally {
            application.deactivate();
        }
    }

    @Test
    public void allBuiltInProfilesCommitThroughTheFullSimulationPath()
            throws Exception {
        RegistrationId simpleRegistration =
                new RegistrationId("A0001");
        RegistrationId normalRegistration =
                new RegistrationId("A0004");
        RegistrationId edgeRegistration =
                new RegistrationId("A0003");

        SimulatedAntenna antenna =
                new SimulatedAntenna();
        TimingApplicationRuntime application =
                SimulationRuntime.create(
                        identity(),
                        config(),
                        antenna,
                        new DefaultEventDataProvider()
                                .createEventData());

        List<TagObservation> observations =
                Collections.synchronizedList(
                        new ArrayList<TagObservation>());
        antenna.tagObservedEvent()
                .subscribe(
                        observations::add);

        List<RegistrationId> committedRegistrations =
                Collections.synchronizedList(
                        new ArrayList<RegistrationId>());
        CountDownLatch threeCommitted =
                new CountDownLatch(3);
        application
                .presentationGateway()
                .timingNode(new NodeId("A"))
                .NEW_DATA_ENTRY
                .subscribe(
                        data -> {
                            if (data.kind()
                                    == TimingRecord.Kind.AUTOMATIC_REGISTRATION) {
                                committedRegistrations.add(
                                        RegistrationId.fromPackedValue(
                                                data.registrationId()));
                                threeCommitted.countDown();
                            }
                        });

        application.activate();
        try {
            await(
                    application.antennaManager()::isReady,
                    2000L);
            TimingNodeProxy node =
                    application
                            .presentationGateway()
                            .timingNode(new NodeId("A"));
            node.open(
                    new LocationId(24));
            await(
                    antenna::inventoryRunning,
                    2000L);

            SimulationControl simulation =
                    application
                            .presentationGateway()
                            .simulation();

            assertEquals(
                    SimulationControl.StartResult.ACCEPTED,
                    simulation.startRegistration(
                            simpleRegistration,
                            "simple"));
            assertEquals(
                    SimulationControl.StartResult.ACCEPTED,
                    simulation.startRegistration(
                            normalRegistration,
                            "normal"));
            assertEquals(
                    SimulationControl.StartResult.ACCEPTED,
                    simulation.startRegistration(
                            edgeRegistration,
                            "edge"));

            assertTrue(
                    threeCommitted.await(
                            3L,
                            TimeUnit.SECONDS));
            assertEquals(
                    3,
                    committedRegistrations.size());
            assertTrue(
                    committedRegistrations.contains(
                            simpleRegistration));
            assertTrue(
                    committedRegistrations.contains(
                            normalRegistration));
            assertTrue(
                    committedRegistrations.contains(
                            edgeRegistration));

            boolean normalTagAObserved = false;
            boolean normalTagBObserved = false;
            boolean edgeTagBObserved = false;
            for (TagObservation observation : observations) {
                String tag =
                        observation.tagId()
                                .value();
                if ("A0004A".equals(tag)) {
                    normalTagAObserved = true;
                } else if ("A0004B".equals(tag)) {
                    normalTagBObserved = true;
                } else if ("A0003B".equals(tag)) {
                    edgeTagBObserved = true;
                }
            }
            assertTrue(
                    "normal profile must exercise the first mapped tag",
                    normalTagAObserved);
            assertTrue(
                    "normal profile must exercise the second mapped tag",
                    normalTagBObserved);
            assertFalse(
                    "A0003 edge variant must remain a single-tag scenario",
                    edgeTagBObserved);

            List<TimingRecord> history =
                    new ArrayList<TimingRecord>();
            assertEquals(
                    4,
                    node.visitLogBookFrom(
                            1L,
                            10,
                            history::add));
            assertEquals(
                    4,
                    history.size());
        } finally {
            application.deactivate();
        }
    }

    private Config config() {
        Path timingData =
                temporaryFolder
                        .getRoot()
                        .toPath()
                        .resolve(
                                "scenario-timing-data.jsonl");
        Config.TimingNodeConfig timingNode =
                new Config.TimingNodeConfig(
                        new NodeId("A"),
                        timingData,
                        new TagProcessingPolicy(
                                Duration.ofMillis(200),
                                Duration.ofMillis(1000),
                                Duration.ofMillis(500),
                                Duration.ofMillis(10),
                                32));
        Config.TimingSystemConfig timingSystem =
                new Config.TimingSystemConfig(
                        "A",
                        Collections.singletonList(
                                timingNode),
                        Config.REFERENCE_PROVIDER_ID,
                        Config.REFERENCE_PROVIDER_ID);
        return new Config(
                Collections.singletonList(
                        timingSystem),
                new Presentation(
                        null,
                        null),
                null,
                null);
    }

    private static EventData eventData() {
        return new DefaultEventDataProvider()
                .createEventData();
    }

    private static BuildIdentity identity() {
        return BuildIdentity.firstApiVersion(
                "event-timing-app",
                "test-version",
                "abc123def456",
                "verification/step5-v08",
                "local",
                false);
    }

    private static void await(
            java.util.function.BooleanSupplier condition,
            long timeoutMillis)
            throws Exception {
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(
                                timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime()
                    >= deadline) {
                throw new AssertionError(
                        "condition did not become true before timeout");
            }
            Thread.sleep(
                    5L);
        }
    }
}
