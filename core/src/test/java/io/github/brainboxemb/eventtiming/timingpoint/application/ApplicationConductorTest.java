package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ApplicationConductorTest {

    @Test
    public void ownsManagerAndSystemLifecycle() {
        ExecutorService nodeWorker =
                Executors.newSingleThreadExecutor();
        ExecutorService systemWorker =
                Executors.newSingleThreadExecutor();
        ScheduledExecutorService tagWorker =
                Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService ioWorker =
                Executors.newSingleThreadScheduledExecutor();

        TimingNode node =
                new TimingNode(
                        new NodeId("A"),
                        new NoOpPersistence(),
                        new DefaultTimingDataFactory(),
                        ApplicationConductorTest::now,
                        ReadOnlyConfiguration.fixed(
                                TagProcessingPolicy.defaults()),
                        EventData.empty(),
                        new SerialExecutor(
                                8,
                                "application-conductor-node-test",
                                nodeWorker),
                        new SerialScheduledExecutor(
                                8,
                                "application-conductor-tag-test",
                                tagWorker));

        AntennaManager manager =
                new AntennaManager(
                        new AntennaSet().add(
                                new AntennaId("1"),
                                new SimulatedAntenna()),
                        new SerialScheduledExecutor(
                                8,
                                "application-conductor-antenna-test",
                                ioWorker),
                        Duration.ofSeconds(1));

        io.github.brainboxemb.eventtiming.timingpoint.domain.system.Conductor
                systemConductor =
                        new io.github.brainboxemb.eventtiming.timingpoint.domain.system.Conductor(
                                Collections.singletonList(node),
                                manager,
                                new SerialExecutor(
                                        8,
                                        "application-conductor-system-test",
                                        systemWorker));

        ApplicationConductor conductor =
                new ApplicationConductor();
        conductor.registerTimingSystem(
                manager,
                systemConductor);

        try {
            conductor.activate();
            assertEquals(
                    State.ACTIVE,
                    manager.state());

            conductor.deactivate();
            assertEquals(
                    State.INACTIVE,
                    manager.state());
        } finally {
            nodeWorker.shutdownNow();
            systemWorker.shutdownNow();
            tagWorker.shutdownNow();
            ioWorker.shutdownNow();
        }
    }

    private static Instant now() {
        return Instant.parse(
                "2026-10-08T12:00:00Z");
    }

    private static final class NoOpPersistence
            implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(
                TimingData data) {
            // Lifecycle test does not persist records.
        }
    }
}
