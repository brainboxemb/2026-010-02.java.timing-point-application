package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.FunctionalState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.Property;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TimingSystemConductorTest {
    private final List<ExecutorService> workers = new ArrayList<ExecutorService>();

    @After
    public void stopWorkers() {
        for (ExecutorService worker : workers) {
            worker.shutdownNow();
        }
    }

    @Test
    public void oneOpenNodeKeepsInventoryEnabledUntilAllNodesClose() throws Exception {
        TimingNode first = newTimingNode("A");
        TimingNode second = newTimingNode("B");
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        SerialTaskRunner lane =
                new SerialTaskRunner(
                        8,
                        "system-conductor-test",
                        newScheduledWorker("worker"));
        TimingSystemConductor conductor = new TimingSystemConductor(
                new TimingNodeList(Arrays.asList(first, second)),
                manager,
                lane);
        activate(manager);
        activate(conductor);
        try {
            first.open(new LocationId(24));
            await(antenna::inventoryRunning, 1000L);

            second.open(new LocationId(25));
            first.close();
            awaitLane(lane);
            assertTrue(antenna.inventoryRunning());

            second.close();
            await(() -> !antenna.inventoryRunning(), 1000L);
        } finally {
            deactivate(conductor);
            deactivate(manager);
        }
    }

    @Test
    public void activationDoesNotWaitForNodeRecoveryAndLaterStatusWakesReconciliation()
            throws Exception {
        BlockingPersistence persistence = new BlockingPersistence();
        TimingNode node = newTimingNode("A", persistence);
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        SerialTaskRunner lane =
                new SerialTaskRunner(
                        4,
                        "conductor-startup-test",
                        newScheduledWorker("conductor-startup-worker"));
        TimingSystemConductor conductor =
                new TimingSystemConductor(
                        new TimingNodeList(Collections.singletonList(node)),
                        manager,
                        lane);
        activate(manager);
        final Throwable[] activationFailure = new Throwable[1];
        Thread activationCaller =
                new Thread(
                        () -> {
                            try {
                                activate(conductor);
                            } catch (Throwable failure) {
                                activationFailure[0] = failure;
                            }
                        },
                        "system-conductor-activation-caller");
        activationCaller.start();

        try {
            assertTrue(
                    "node recovery did not start",
                    persistence.loadStarted.await(
                            1L,
                            TimeUnit.SECONDS));
            activationCaller.join(250L);
            assertFalse(
                    "TimingSystemConductor activation waited for TimingNode recovery",
                    activationCaller.isAlive());
            assertNull(activationFailure[0]);
            assertEquals(FunctionalState.CLOSED, node.functionalState());
            assertFalse(antenna.inventoryRunning());

            persistence.releaseLoad.countDown();

            node.open(new LocationId(24));
            await(
                    antenna::inventoryRunning,
                    1000L);
            assertTrue(antenna.inventoryRunning());
        } finally {
            persistence.releaseLoad.countDown();
            activationCaller.join(1000L);
            if (!activationCaller.isAlive()) {
                deactivate(conductor);
            }
            deactivate(manager);
        }
    }

    @Test
    public void statusEventOnlyWakesConductorOnNodeThread() throws Exception {
        TimingNode node = newTimingNode("A");
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        SerialTaskRunner lane =
                new SerialTaskRunner(
                        4,
                        "conductor-event-test",
                        newScheduledWorker("conductor-event-worker"));
        TimingSystemConductor conductor = new TimingSystemConductor(
                new TimingNodeList(Collections.singletonList(node)), manager, lane);
        activate(manager);
        activate(conductor);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            await(() -> lane.metrics().snapshot().queueDepth() == 0, 1000L);
            runOneStep(lane, () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            node.open(new LocationId(24));
            assertFalse(
                    "Node event only wakes the Conductor",
                    antenna.inventoryRunning());
            assertEquals(
                    "Node event queues one Conductor reconciliation",
                    1,
                    lane.metrics().snapshot().queueDepth());
            release.countDown();
            await(antenna::inventoryRunning, 1000L);
        } finally {
            release.countDown();
            deactivate(conductor);
            deactivate(manager);
        }
    }

    @Test
    public void unrelatedNodePropertyDoesNotWakeReconciliation() throws Exception {
        TimingNode node = newTimingNode("A");
        Property<Boolean> diagnostic =
                node.PROPERTIES.property("diagnostic", Boolean.FALSE);
        SerialTaskRunner lane =
                new SerialTaskRunner(
                        4,
                        "conductor-filter-test",
                        newScheduledWorker("conductor-filter-worker"));
        TimingSystemConductor conductor =
                new TimingSystemConductor(
                        new TimingNodeList(Collections.singletonList(node)),
                        null,
                        lane);
        activate(conductor);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            await(() -> lane.metrics().snapshot().queueDepth() == 0, 1000L);
            runOneStep(lane, () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));

            diagnostic.set(Boolean.TRUE);
            node.PROPERTIES.update();

            assertEquals(
                    "unrelated property change must not wake conductor",
                    0,
                    lane.metrics().snapshot().queueDepth());
        } finally {
            release.countDown();
            deactivate(conductor);
        }
    }

    @Test
    public void rapidNodeChangesCoalesceToCurrentOpenState() throws Exception {
        TimingNode node = newTimingNode("A");
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        SerialTaskRunner lane =
                new SerialTaskRunner(
                        4,
                        "conductor-coalesce-test",
                        newScheduledWorker("coalesce-worker"));
        TimingSystemConductor conductor = new TimingSystemConductor(
                new TimingNodeList(Collections.singletonList(node)), manager, lane);
        activate(manager);
        activate(conductor);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            await(() -> lane.metrics().snapshot().queueDepth() == 0, 1000L);
            runOneStep(lane, () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            node.open(new LocationId(24));
            node.close();
            node.open(new LocationId(25));
            assertFalse(antenna.inventoryRunning());
            assertEquals("one coalesced pending reconciliation", 1,
                    lane.metrics().snapshot().queueDepth());
            release.countDown();
            await(antenna::inventoryRunning, 1000L);
            assertEquals(
                    "coalesced reconciliation starts inventory once",
                    1,
                    antenna.inventoryStartCount());
        } finally {
            release.countDown();
            deactivate(conductor);
            deactivate(manager);
        }
    }

    @Test
    public void timingNodeListPreservesRegistrationOrder() {
        TimingNode first = newTimingNode("A");
        TimingNode second = newTimingNode("B");

        TimingNodeList nodes = new TimingNodeList(Arrays.asList(first, second));

        assertEquals(2, nodes.size());
        assertSame(first, nodes.get(0));
        assertSame(second, nodes.get(1));
    }

    @Test
    public void timingNodeListRejectsDuplicateInstance() {
        TimingNode node = newTimingNode("A");
        try {
            new TimingNodeList(Arrays.asList(node, node));
            fail("Duplicate TimingNode accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("already present"));
        }
    }

    private TimingNode newTimingNode(String id) {
        return TimingNodeFixture.create(
                new NodeId(id),
                new NoOpPersistence(),
                TimingSystemConductorTest::now);
    }

    private TimingNode newTimingNode(
            String id,
            TimingDataPersistence persistence) {
        return TimingNodeFixture.create(
                new NodeId(id),
                persistence,
                TimingSystemConductorTest::now);
    }

    private AntennaManager newAntennaManager(SimulatedAntenna antenna) {
        ScheduledExecutorService worker =
                newScheduledWorker("conductor-antenna-test");
        return new AntennaManager(
                new AntennaSet().add(new AntennaId("1"), antenna),
                new SerialTaskRunner(
                        8,
                        "conductor-antenna-test",
                        worker,
                        Duration.ofSeconds(1)));
    }

    private ScheduledExecutorService newScheduledWorker(String name) {
        ScheduledExecutorService worker =
                Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, name));
        workers.add(worker);
        return worker;
    }

    private static void runOneStep(SerialTaskRunner lane, Runnable action) {
        lane.runTask(() -> {
            action.run();
            return TaskStep.done();
        });
    }

    private static void awaitLane(SerialTaskRunner lane) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        runOneStep(lane, completed::countDown);
        assertTrue("Conductor lane did not drain", completed.await(1L, TimeUnit.SECONDS));
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition did not become true before timeout");
            }
            Thread.sleep(2L);
        }
    }

    private static Instant now() {
        return Instant.parse("2026-10-06T09:00:00Z");
    }

    private static final class BlockingPersistence implements TimingDataPersistence {
        private final CountDownLatch loadStarted =
                new CountDownLatch(1);
        private final CountDownLatch releaseLoad =
                new CountDownLatch(1);

        @Override
        public LoadResult load()
                throws PersistenceException {
            loadStarted.countDown();
            try {
                releaseLoad.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new PersistenceException(
                        "test recovery interrupted",
                        ex);
            }
            return new LoadResult(
                    Collections.<TimingRecord>emptyList(),
                    false);
        }

        @Override
        public void append(TimingRecord record) {
            // Startup coordination test only.
        }
    }

    private static final class NoOpPersistence implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(Collections.<TimingRecord>emptyList(), false);
        }

        @Override
        public void append(TimingRecord record) {
            // TimingSystemConductor tests exercise coordination, not persistence.
        }
    }
}
