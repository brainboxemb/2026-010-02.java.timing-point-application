package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.eventdata.simulation.SimulationEventDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.AutomaticRegistration;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.application.SimulationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Presentation;

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
            new RegistrationId("RT-A-0042");
    private static final TagId TAG_A =
            new TagId("TT-A-0042-1");
    private static final TagId TAG_B =
            new TagId("TT-A-0042-2");

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

        AtomicReference<AutomaticRegistration> committed =
                new AtomicReference<AutomaticRegistration>();
        CountDownLatch registrationCommitted =
                new CountDownLatch(1);
        application
                .presentationGateway()
                .timingNode(new NodeId("A"))
                .timingDataCommittedEvent()
                .subscribe(
                        data -> {
                            if (data instanceof AutomaticRegistration) {
                                committed.set(
                                        (AutomaticRegistration) data);
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

            AutomaticRegistration registration =
                    committed.get();
            assertEquals(
                    REGISTRATION,
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
                    strongest.observedAt().instant();
            long expectedNanos =
                    (strongestInstant.getNano()
                            / 10_000_000L)
                            * 10_000_000L;
            assertEquals(
                    Instant.ofEpochSecond(
                            strongestInstant.getEpochSecond(),
                            expectedNanos),
                    registration.effectiveTime().instant());

            List<TimingData> history =
                    new ArrayList<TimingData>();
            assertEquals(
                    2,
                    node.visitLogBookFrom(
                            1L,
                            10,
                            history::add));
            assertEquals(
                    2,
                    history.size());
            assertTrue(
                    history.get(1)
                            instanceof AutomaticRegistration);
        } finally {
            application.deactivate();
        }
    }

    @Test
    public void allBuiltInProfilesCommitThroughTheFullSimulationPath()
            throws Exception {
        RegistrationId simpleRegistration =
                new RegistrationId("RT-A-0001");
        RegistrationId normalRegistration =
                new RegistrationId("RT-A-0004");
        RegistrationId edgeRegistration =
                new RegistrationId("RT-A-0003");

        SimulatedAntenna antenna =
                new SimulatedAntenna();
        TimingApplicationRuntime application =
                SimulationRuntime.create(
                        identity(),
                        config(),
                        antenna,
                        new SimulationEventDataProvider()
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
                .timingDataCommittedEvent()
                .subscribe(
                        data -> {
                            if (data instanceof AutomaticRegistration) {
                                AutomaticRegistration registration =
                                        (AutomaticRegistration) data;
                                committedRegistrations.add(
                                        registration.registrationId());
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
                if ("TT-A-0004-1".equals(tag)) {
                    normalTagAObserved = true;
                } else if ("TT-A-0004-2".equals(tag)) {
                    normalTagBObserved = true;
                } else if ("TT-A-0003-2".equals(tag)) {
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
                    "RT-A-0003 edge variant must remain a single-tag scenario",
                    edgeTagBObserved);

            List<TimingData> history =
                    new ArrayList<TimingData>();
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
        return new Config(
                new NodeId("A"),
                new Presentation(
                        null,
                        null),
                null,
                null,
                timingData,
                new TagProcessingPolicy(
                        Duration.ofMillis(200),
                        Duration.ofMillis(1000),
                        Duration.ofMillis(500),
                        Duration.ofMillis(10),
                        32));
    }

    private static EventData eventData() {
        return new SimulationEventDataProvider()
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
