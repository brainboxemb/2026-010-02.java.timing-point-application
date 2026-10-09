package io.github.brainboxemb.eventtiming.timingpoint.runtime.simulation;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.application.SimulationControl;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SimulatedTagScenarioRunnerTest {
    private static final RegistrationId REGISTRATION =
            new RegistrationId("N0042");
    private static final TagId TAG_A =
            new TagId("N0042-A");
    private static final TagId TAG_B =
            new TagId("N0042-B");
    private static final Instant BASE_TIME =
            Instant.parse("2026-10-07T12:00:00Z");

    @Test
    public void normalProfileEmitsTwoMappedTagsThroughSimulatedAntenna()
            throws Exception {
        ScheduledThreadPoolExecutor worker =
                new ScheduledThreadPoolExecutor(1);
        worker.setRemoveOnCancelPolicy(true);
        SerialScheduledExecutor lane =
                new SerialScheduledExecutor(
                        32,
                        "simulation-test",
                        worker);
        SimulatedAntenna antenna =
                runningAntenna();
        SimulatedTagScenarioRunner runner =
                new SimulatedTagScenarioRunner(
                        antenna,
                        eventData(),
                        () -> BASE_TIME,
                        lane);

        List<TagObservation> observations =
                Collections.synchronizedList(
                        new ArrayList<TagObservation>());
        CountDownLatch emitted =
                new CountDownLatch(4);
        antenna.tagObservedEvent()
                .subscribe(
                        observation -> {
                            observations.add(
                                    observation);
                            emitted.countDown();
                        });

        runner.activate();
        try {
            assertEquals(
                    SimulationControl.StartResult.ACCEPTED,
                    runner.startRegistration(
                            REGISTRATION,
                            "normal"));

            assertTrue(
                    emitted.await(
                            2L,
                            TimeUnit.SECONDS));
            assertEquals(
                    4,
                    observations.size());
            assertEquals(
                    TAG_A,
                    observations.get(0)
                            .tagId());
            assertEquals(
                    TAG_B,
                    observations.get(1)
                            .tagId());
            assertEquals(
                    TAG_A,
                    observations.get(2)
                            .tagId());
            assertEquals(
                    TAG_B,
                    observations.get(3)
                            .tagId());
            assertEquals(
                    BASE_TIME,
                    observations.get(0)
                            .observedAt()
                            .instant());
            assertEquals(
                    BASE_TIME.plusMillis(80L),
                    observations.get(2)
                            .observedAt()
                            .instant());
        } finally {
            runner.deactivate();
            antenna.shutdown();
            worker.shutdownNow();
        }
    }

    @Test
    public void simpleProfileEmitsOneObservationFromFirstMappedTag()
            throws Exception {
        ScheduledThreadPoolExecutor worker =
                new ScheduledThreadPoolExecutor(1);
        worker.setRemoveOnCancelPolicy(true);
        SerialScheduledExecutor lane =
                new SerialScheduledExecutor(
                        32,
                        "simulation-test",
                        worker);
        SimulatedAntenna antenna =
                runningAntenna();
        SimulatedTagScenarioRunner runner =
                new SimulatedTagScenarioRunner(
                        antenna,
                        eventData(),
                        () -> BASE_TIME,
                        lane);

        AtomicObservation single =
                new AtomicObservation();
        CountDownLatch emitted =
                new CountDownLatch(1);
        antenna.tagObservedEvent()
                .subscribe(
                        observation -> {
                            single.value =
                                    observation;
                            emitted.countDown();
                        });

        runner.activate();
        try {
            assertEquals(
                    SimulationControl.StartResult.ACCEPTED,
                    runner.startRegistration(
                            REGISTRATION,
                            "simple"));
            assertTrue(
                    emitted.await(
                            1L,
                            TimeUnit.SECONDS));
            assertEquals(
                    TAG_A,
                    single.value.tagId());
            assertEquals(
                    BASE_TIME,
                    single.value.observedAt()
                            .instant());
        } finally {
            runner.deactivate();
            antenna.shutdown();
            worker.shutdownNow();
        }
    }

    @Test
    public void edgeProfileDeterministicallyCoversLongAndSingleTagShapes()
            throws Exception {
        RegistrationId longRegistration =
                new RegistrationId("N0001");
        RegistrationId singleRegistration =
                new RegistrationId("N0002");
        TagId longA =
                new TagId("N0001-A");
        TagId longB =
                new TagId("N0001-B");
        TagId singleA =
                new TagId("N0002-A");
        TagId singleB =
                new TagId("N0002-B");

        Map<TagId, RegistrationId> mapping =
                new LinkedHashMap<TagId, RegistrationId>();
        mapping.put(longA, longRegistration);
        mapping.put(longB, longRegistration);
        mapping.put(singleA, singleRegistration);
        mapping.put(singleB, singleRegistration);

        ScheduledThreadPoolExecutor worker =
                new ScheduledThreadPoolExecutor(1);
        worker.setRemoveOnCancelPolicy(true);
        SerialScheduledExecutor lane =
                new SerialScheduledExecutor(
                        32,
                        "simulation-test",
                        worker);
        SimulatedAntenna antenna =
                runningAntenna();
        SimulatedTagScenarioRunner runner =
                new SimulatedTagScenarioRunner(
                        antenna,
                        new EventData(mapping),
                        () -> BASE_TIME,
                        lane);

        List<TagObservation> longObservations =
                Collections.synchronizedList(
                        new ArrayList<TagObservation>());
        List<TagObservation> singleObservations =
                Collections.synchronizedList(
                        new ArrayList<TagObservation>());
        CountDownLatch longEmitted =
                new CountDownLatch(5);
        CountDownLatch singleEmitted =
                new CountDownLatch(3);
        antenna.tagObservedEvent()
                .subscribe(
                        observation -> {
                            if (observation.tagId()
                                    .value()
                                    .startsWith("N0001")) {
                                longObservations.add(
                                        observation);
                                longEmitted.countDown();
                            } else if (observation.tagId()
                                    .value()
                                    .startsWith("N0002")) {
                                singleObservations.add(
                                        observation);
                                singleEmitted.countDown();
                            }
                        });

        runner.activate();
        try {
            assertEquals(
                    SimulationControl.StartResult.ACCEPTED,
                    runner.startRegistration(
                            longRegistration,
                            "edge"));
            assertEquals(
                    SimulationControl.StartResult.ACCEPTED,
                    runner.startRegistration(
                            singleRegistration,
                            "edge"));

            assertTrue(
                    singleEmitted.await(
                            1L,
                            TimeUnit.SECONDS));
            assertTrue(
                    longEmitted.await(
                            2L,
                            TimeUnit.SECONDS));

            assertEquals(
                    5,
                    longObservations.size());
            assertEquals(
                    BASE_TIME.plusMillis(800L),
                    longObservations.get(4)
                            .observedAt()
                            .instant());
            assertEquals(
                    longA,
                    longObservations.get(0)
                            .tagId());
            assertEquals(
                    longB,
                    longObservations.get(1)
                            .tagId());

            assertEquals(
                    3,
                    singleObservations.size());
            assertEquals(
                    singleA,
                    singleObservations.get(0)
                            .tagId());
            assertEquals(
                    singleA,
                    singleObservations.get(1)
                            .tagId());
            assertEquals(
                    singleA,
                    singleObservations.get(2)
                            .tagId());
        } finally {
            runner.deactivate();
            antenna.shutdown();
            worker.shutdownNow();
        }
    }

    @Test
    public void rejectsUnknownRegistrationProfileAndInactiveInventory() {
        ScheduledThreadPoolExecutor worker =
                new ScheduledThreadPoolExecutor(1);
        worker.setRemoveOnCancelPolicy(true);
        SerialScheduledExecutor lane =
                new SerialScheduledExecutor(
                        32,
                        "simulation-test",
                        worker);
        SimulatedAntenna antenna =
                new SimulatedAntenna();
        SimulatedTagScenarioRunner runner =
                new SimulatedTagScenarioRunner(
                        antenna,
                        eventData(),
                        () -> BASE_TIME,
                        lane);

        runner.activate();
        try {
            assertEquals(
                    SimulationControl.StartResult.UNKNOWN_REGISTRATION,
                    runner.startRegistration(
                            new RegistrationId("N9999"),
                            "normal"));
            assertEquals(
                    SimulationControl.StartResult.UNKNOWN_PROFILE,
                    runner.startRegistration(
                            REGISTRATION,
                            "missing"));
            assertEquals(
                    SimulationControl.StartResult.UNAVAILABLE,
                    runner.startRegistration(
                            REGISTRATION,
                            "simple"));
        } finally {
            runner.deactivate();
            antenna.shutdown();
            worker.shutdownNow();
        }
    }

    private static final class AtomicObservation {
        private volatile TagObservation value;
    }

    private static SimulatedAntenna runningAntenna() {
        SimulatedAntenna antenna =
                new SimulatedAntenna();
        antenna.initialize();
        antenna.startInventory();
        return antenna;
    }

    private static EventData eventData() {
        Map<TagId, RegistrationId> mapping =
                new LinkedHashMap<TagId, RegistrationId>();
        mapping.put(
                TAG_A,
                REGISTRATION);
        mapping.put(
                TAG_B,
                REGISTRATION);
        return new EventData(
                mapping);
    }
}
