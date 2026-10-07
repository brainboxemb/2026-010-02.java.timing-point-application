package io.github.brainboxemb.eventtiming.timingpoint.runtime;

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
