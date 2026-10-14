package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SystemConductorTest {
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
        SerialLane lane = new SerialLane(8, "system-conductor-test", newWorker("worker"));
        SystemConductor conductor = new SystemConductor(
                new TimingNodeList().add(first).add(second), manager.settings(), lane);
        first.logBook().statusChangedEvent().subscribe(status -> conductor.onTimingNodeStatusChanged(first, status));
        second.logBook().statusChangedEvent().subscribe(status -> conductor.onTimingNodeStatusChanged(second, status));

        manager.activate();
        conductor.activate();
        try {
            first.logBook().process(LogBookCommands.open(new LocationId(24)));
            await(antenna::inventoryRunning, 1000L);

            second.logBook().process(LogBookCommands.open(new LocationId(25)));
            first.logBook().process(LogBookCommands.close());
            await(() -> manager.settings().inventoryEnabled(), 1000L);
            assertTrue(antenna.inventoryRunning());

            second.logBook().process(LogBookCommands.close());
            await(() -> !antenna.inventoryRunning(), 1000L);
        } finally {
            conductor.deactivate();
            manager.deactivate();
        }
    }

    @Test
    public void activationDoesNotWaitForNodeRecoveryAndLaterStatusWakesReconciliation()
            throws Exception {
        BlockingPersistence persistence = new BlockingPersistence();
        TimingNode node = newTimingNode("A", persistence);
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        SerialLane lane =
                new SerialLane(
                        4,
                        "conductor-startup-test",
                        newWorker("conductor-startup-worker"));
        SystemConductor conductor =
                new SystemConductor(
                        new TimingNodeList().add(node),
                        manager.settings(),
                        lane);
        node.logBook().statusChangedEvent().subscribe(
                status -> conductor.onTimingNodeStatusChanged(node, status));

        manager.activate();
        final Throwable[] activationFailure = new Throwable[1];
        Thread activationCaller =
                new Thread(
                        () -> {
                            try {
                                conductor.activate();
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
                    "SystemConductor activation waited for TimingNode recovery",
                    activationCaller.isAlive());
            assertNull(activationFailure[0]);
            assertEquals(
                    "no partial inventory decision before node readiness",
                    0L,
                    manager.settings().revision());

            node.logBook().execute(
                    LogBookCommands.open(
                            new LocationId(24)));

            persistence.releaseLoad.countDown();
            await(
                    antenna::inventoryRunning,
                    1000L);
            assertTrue(manager.settings().inventoryEnabled());
        } finally {
            persistence.releaseLoad.countDown();
            activationCaller.join(1000L);
            if (!activationCaller.isAlive()) {
                conductor.deactivate();
            }
            manager.deactivate();
        }
    }

    @Test
    public void statusEventWakesConductorWithoutUpdatingSettingsOnNodeThread() throws Exception {
        TimingNode node = newTimingNode("A");
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        SerialLane lane = new SerialLane(4, "conductor-event-test", newWorker("conductor-event-worker"));
        SystemConductor conductor = new SystemConductor(
                new TimingNodeList().add(node), manager.settings(), lane);
        node.logBook().statusChangedEvent().subscribe(status -> conductor.onTimingNodeStatusChanged(node, status));

        manager.activate();
        conductor.activate();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            await(() -> lane.metrics().snapshot().queueDepth() == 0, 1000L);
            lane.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            node.logBook().process(LogBookCommands.open(new LocationId(24)));
            assertFalse("Node event only wakes the Conductor", manager.settings().inventoryEnabled());
            release.countDown();
            await(antenna::inventoryRunning, 1000L);
        } finally {
            release.countDown();
            conductor.deactivate();
            manager.deactivate();
        }
    }

    @Test
    public void rapidNodeChangesCoalesceToCurrentOpenState() throws Exception {
        TimingNode node = newTimingNode("A");
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        SerialLane lane = new SerialLane(4, "conductor-coalesce-test", newWorker("coalesce-worker"));
        SystemConductor conductor = new SystemConductor(
                new TimingNodeList().add(node), manager.settings(), lane);
        node.logBook().statusChangedEvent().subscribe(status -> conductor.onTimingNodeStatusChanged(node, status));

        manager.activate();
        conductor.activate();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            await(() -> lane.metrics().snapshot().queueDepth() == 0, 1000L);
            lane.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(started.await(1L, TimeUnit.SECONDS));
            node.logBook().process(LogBookCommands.open(new LocationId(24)));
            node.logBook().process(LogBookCommands.close());
            node.logBook().process(LogBookCommands.open(new LocationId(25)));
            assertFalse(manager.settings().inventoryEnabled());
            assertEquals("one coalesced pending reconciliation", 1,
                    lane.metrics().snapshot().queueDepth());
            release.countDown();
            await(antenna::inventoryRunning, 1000L);
            assertTrue(manager.settings().inventoryEnabled());
            assertEquals("no intermediate OPEN/CLOSE publication", 1L,
                    manager.settings().revision());
        } finally {
            release.countDown();
            conductor.deactivate();
            manager.deactivate();
        }
    }

    @Test
    public void timingNodeListPreservesRegistrationOrder() {
        TimingNode first = newTimingNode("A");
        TimingNode second = newTimingNode("B");

        TimingNodeList nodes = new TimingNodeList().add(first).add(second);

        assertEquals(2, nodes.size());
        assertSame(first, nodes.get(0));
        assertSame(second, nodes.get(1));
    }

    @Test
    public void timingNodeListRejectsDuplicateInstance() {
        TimingNode node = newTimingNode("A");
        TimingNodeList nodes = new TimingNodeList().add(node);

        try {
            nodes.add(node);
            fail("Duplicate TimingNode accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals(1, nodes.size());
        }
    }

    private TimingNode newTimingNode(String id) {
        return TimingNodeFixture.create(
                new NodeId(id),
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                SystemConductorTest::now);
    }

    private TimingNode newTimingNode(
            String id,
            TimingDataPersistence persistence) {
        return TimingNodeFixture.create(
                new NodeId(id),
                persistence,
                new DefaultTimingDataFactory(),
                SystemConductorTest::now);
    }

    private AntennaManager newAntennaManager(SimulatedAntenna antenna) {
        ScheduledExecutorService worker =
                newScheduledWorker("conductor-antenna-test");
        return new AntennaManager(
                new AntennaSet().add(new AntennaId("1"), antenna),
                new SerialLane(
                        8,
                        "conductor-antenna-test",
                        worker),
                worker,
                Duration.ofSeconds(1));
    }

    private ExecutorService newWorker(String name) {
        ExecutorService worker =
                Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, name));
        workers.add(worker);
        return worker;
    }

    private ScheduledExecutorService newScheduledWorker(String name) {
        ScheduledExecutorService worker =
                Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, name));
        workers.add(worker);
        return worker;
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
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data) {
            // Startup coordination test only.
        }
    }

    private static final class NoOpPersistence implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(Collections.<TimingData>emptyList(), false);
        }

        @Override
        public void append(TimingData data) {
            // SystemConductor tests exercise coordination, not persistence.
        }
    }
}
