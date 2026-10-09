package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.SystemConductor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ApplicationConductorTest {

    @Test
    public void ownsManagerAndSystemLifecycle() {
        SystemFixture system =
                new SystemFixture("A", "one");
        ApplicationConductor conductor =
                new ApplicationConductor();
        conductor.registerTimingSystem(
                system.manager,
                system.conductor);

        try {
            conductor.activate();
            assertEquals(
                    State.ACTIVE,
                    system.manager.state());

            conductor.deactivate();
            assertEquals(
                    State.INACTIVE,
                    system.manager.state());
        } finally {
            system.close();
        }
    }

    @Test
    public void activatesEveryRegisteredTimingSystem() {
        SystemFixture first =
                new SystemFixture("A", "one");
        SystemFixture second =
                new SystemFixture("B", "two");
        ApplicationConductor conductor =
                new ApplicationConductor();
        conductor.registerTimingSystem(
                first.manager,
                first.conductor);
        conductor.registerTimingSystem(
                second.manager,
                second.conductor);

        try {
            conductor.activate();
            assertEquals(
                    State.ACTIVE,
                    first.manager.state());
            assertEquals(
                    State.ACTIVE,
                    second.manager.state());

            conductor.deactivate();
            assertEquals(
                    State.INACTIVE,
                    first.manager.state());
            assertEquals(
                    State.INACTIVE,
                    second.manager.state());
        } finally {
            first.close();
            second.close();
        }
    }

    private static Instant now() {
        return Instant.parse(
                "2026-10-08T12:00:00Z");
    }

    private static final class SystemFixture
            implements AutoCloseable {
        private final ExecutorService nodeWorker =
                Executors.newSingleThreadExecutor();
        private final ExecutorService systemWorker =
                Executors.newSingleThreadExecutor();
        private final ScheduledExecutorService tagWorker =
                Executors.newSingleThreadScheduledExecutor();
        private final ScheduledExecutorService ioWorker =
                Executors.newSingleThreadScheduledExecutor();

        private final AntennaManager manager;
        private final SystemConductor conductor;

        private SystemFixture(
                String nodeId,
                String label) {
            TimingNode node =
                    new TimingNode(
                            new NodeId(nodeId),
                            new NoOpPersistence(),
                            new DefaultTimingDataFactory(),
                            ApplicationConductorTest::now,
                            ReadOnlyConfiguration.fixed(
                                    TagProcessingPolicy.defaults()),
                            EventData.empty(),
                            new SerialExecutor(
                                    8,
                                    "app-node-" + label,
                                    nodeWorker),
                            new SerialScheduledExecutor(
                                    8,
                                    "app-tag-" + label,
                                    tagWorker));

            manager =
                    new AntennaManager(
                            new AntennaSet().add(
                                    new AntennaId("1"),
                                    new SimulatedAntenna()),
                            new SerialScheduledExecutor(
                                    8,
                                    "app-antenna-" + label,
                                    ioWorker),
                            Duration.ofSeconds(1));

            TimingNodeList timingNodes =
                    new TimingNodeList().add(node);
            conductor =
                    new SystemConductor(
                            timingNodes,
                            manager,
                            new SerialExecutor(
                                    8,
                                    "app-system-" + label,
                                    systemWorker));
        }

        @Override
        public void close() {
            nodeWorker.shutdownNow();
            systemWorker.shutdownNow();
            tagWorker.shutdownNow();
            ioWorker.shutdownNow();
        }
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
