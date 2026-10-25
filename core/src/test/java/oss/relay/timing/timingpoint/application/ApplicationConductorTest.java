package oss.relay.timing.timingpoint.application;

import oss.relay.timing.eventdata.EventData;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingpoint.domain.system.TimingSystem;
import oss.relay.timing.timingpoint.domain.system.upstream.Upstream;
import oss.relay.timing.timingpoint.domain.system.TimingSystemConductor;
import oss.relay.timing.timingpoint.domain.node.TimingNode;
import oss.relay.timing.timingpoint.domain.node.TimingNodeList;
import oss.relay.timing.timingpoint.testsupport.TimingNodeFixture;
import oss.relay.timing.timingpoint.domain.node.processing.TagProcessingPolicy;
import oss.relay.timing.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import oss.relay.timing.timingpoint.infra.configuration.ReadOnlyConfiguration;
import oss.relay.timing.timingpoint.io.devices.antenna.AntennaId;
import oss.relay.timing.timingpoint.io.devices.antenna.manager.AntennaManager;
import oss.relay.timing.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import oss.relay.timing.timingpoint.io.devices.antenna.manager.AntennaSet;
import oss.relay.timing.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import oss.relay.timing.timingpoint.platform.execution.lanes.SerialLane;
import oss.relay.timing.timingpoint.platform.execution.tasks.SerialTaskRunner;

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
        conductor.registerTimingSystem(system.system);

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
        conductor.registerTimingSystem(first.system);
        conductor.registerTimingSystem(second.system);

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
        private final ScheduledExecutorService systemWorker =
                Executors.newSingleThreadScheduledExecutor();
        private final ScheduledExecutorService ioWorker =
                Executors.newSingleThreadScheduledExecutor();

        private final AntennaManager manager;
        private final TimingSystemConductor timingSystemConductor;
        private final TimingSystem system;

        private SystemFixture(
                String nodeId,
                String label) {
            TimingNode node =
                    TimingNodeFixture.create(
                            new NodeId(nodeId),
                            new NoOpPersistence(),
                            ApplicationConductorTest::now);

            manager =
                    new AntennaManager(
                            new AntennaSet().add(
                                    new AntennaId("1"),
                                    new SimulatedAntenna()),
                            new SerialTaskRunner(
                                    8,
                                    "app-antenna-" + label,
                                    ioWorker,
                                    Duration.ofSeconds(1)));

            TimingNodeList timingNodes =
                    new TimingNodeList(Collections.singletonList(node));
            timingSystemConductor =
                    new TimingSystemConductor(
                            timingNodes,
                            manager,
                            new SerialTaskRunner(
                                    8,
                                    "app-system-" + label,
                                    systemWorker));
            system = new TimingSystem(
                    label,
                    timingNodes,
                    manager,
                    timingSystemConductor,
                    new Upstream(Collections.emptyList()));
        }

        @Override
        public void close() {
            systemWorker.shutdownNow();
            ioWorker.shutdownNow();
        }
    }

    private static final class NoOpPersistence
            implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingRecord>emptyList(),
                    false);
        }

        @Override
        public void append(
                TimingRecord record) {
            // Lifecycle test does not persist records.
        }
    }
}
