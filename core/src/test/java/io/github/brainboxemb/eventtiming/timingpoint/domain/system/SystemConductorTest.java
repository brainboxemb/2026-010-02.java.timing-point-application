package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.SourceProperty;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

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
    public void activationStartsFirstControlRunWithoutWaitingForSourceRead() throws Exception {
        TimingNode node = newTimingNode("A");
        ExecutorService conductorWorker = newWorker("conductor-blocked-startup");
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        conductorWorker.submit(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

        SerialExecutor lane = new SerialExecutor(4, "conductor-test", conductorWorker);
        SystemConductor conductor = new SystemConductor(new TimingNodeList().add(node), null, lane);

        try {
            conductor.activate();

            assertFalse(
                    "source state belongs to the first control run, not activate()",
                    conductor.nodeStateProperty(node).initialized());

            releaseBlocker.countDown();
            await(() -> conductor.nodeStateProperty(node).initialized(), 1000L);
            assertEquals(State.CLOSED, conductor.nodeStateProperty(node).currentValue());
        } finally {
            releaseBlocker.countDown();
            conductor.deactivate();
        }
    }

    @Test
    public void statusEventOnlyWakesThenControlRunRefreshesCurrentState() throws Exception {
        TimingNode node = newTimingNode("A");
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        ExecutorService conductorWorker = newWorker("conductor-event-worker");
        SerialExecutor lane = new SerialExecutor(4, "conductor-test", conductorWorker);
        SystemConductor conductor = new SystemConductor(new TimingNodeList().add(node), manager, lane);
        node.statusChangedEvent().subscribe(status -> conductor.onTimingNodeStatusChanged(node, status));

        manager.activate();
        conductor.activate();
        try {
            await(() -> conductor.nodeStateProperty(node).initialized(), 1000L);

            CountDownLatch blockerStarted = new CountDownLatch(1);
            CountDownLatch releaseBlocker = new CountDownLatch(1);
            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    lane.offer(() -> {
                        blockerStarted.countDown();
                        try {
                            releaseBlocker.await();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            assertEquals(
                    "event callback must not directly mutate the SourceProperty",
                    State.CLOSED,
                    conductor.nodeStateProperty(node).currentValue());

            releaseBlocker.countDown();
            await(
                    () -> conductor.nodeStateProperty(node).currentValue() == State.OPEN,
                    1000L);
            await(antenna::inventoryRunning, 1000L);
        } finally {
            conductor.deactivate();
            manager.deactivate();
        }
    }

    @Test
    public void rapidStateEventsCoalesceAndRefreshLatestCurrentState() throws Exception {
        TimingNode node = newTimingNode("A");
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        ExecutorService conductorWorker = newWorker("conductor-blocked-test");
        SerialExecutor lane = new SerialExecutor(1, "conductor-test", conductorWorker);
        SystemConductor conductor = new SystemConductor(new TimingNodeList().add(node), manager, lane);
        node.statusChangedEvent().subscribe(status -> conductor.onTimingNodeStatusChanged(node, status));

        manager.activate();
        conductor.activate();

        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        try {
            await(() -> conductor.nodeStateProperty(node).initialized(), 1000L);
            await(() -> lane.metrics().snapshot().queueDepth() == 0, 1000L);

            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    lane.offer(() -> {
                        blockerStarted.countDown();
                        try {
                            releaseBlocker.await();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }));
            assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

            node.invoke(TimingNodeCommands.open(new LocationId(24)));
            node.invoke(TimingNodeCommands.close());
            node.invoke(TimingNodeCommands.open(new LocationId(25)));

            assertEquals(1, lane.metrics().snapshot().queueDepth());
            assertEquals(0L, lane.metrics().snapshot().fullCount());
            assertEquals(
                    "SourceProperty stays at the last reconciled value while the control lane is blocked",
                    State.CLOSED,
                    conductor.nodeStateProperty(node).currentValue());

            releaseBlocker.countDown();
            await(
                    () -> conductor.nodeStateProperty(node).currentValue() == State.OPEN,
                    1000L);
            await(antenna::inventoryRunning, 1000L);
        } finally {
            releaseBlocker.countDown();
            conductor.deactivate();
            manager.deactivate();
        }
    }

    @Test
    public void oneOpenNodeKeepsSharedInventoryEnabled() throws Exception {
        TimingNode first = newTimingNode("A");
        TimingNode second = newTimingNode("B");
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        SerialExecutor lane = new SerialExecutor(8, "system-conductor-test", newWorker("system-conductor-worker"));
        SystemConductor conductor =
                new SystemConductor(new TimingNodeList().add(first).add(second), manager, lane);
        first.statusChangedEvent().subscribe(status -> conductor.onTimingNodeStatusChanged(first, status));
        second.statusChangedEvent().subscribe(status -> conductor.onTimingNodeStatusChanged(second, status));

        manager.activate();
        conductor.activate();
        try {
            await(() -> conductor.nodeStateProperty(first).initialized(), 1000L);
            await(() -> conductor.nodeStateProperty(second).initialized(), 1000L);

            first.invoke(TimingNodeCommands.open(new LocationId(24)));
            await(antenna::inventoryRunning, 1000L);

            second.invoke(TimingNodeCommands.open(new LocationId(25)));
            await(() -> conductor.nodeStateProperty(second).currentValue() == State.OPEN, 1000L);

            first.invoke(TimingNodeCommands.close());
            await(() -> conductor.nodeStateProperty(first).currentValue() == State.CLOSED, 1000L);
            assertTrue(antenna.inventoryRunning());

            second.invoke(TimingNodeCommands.close());
            await(() -> !antenna.inventoryRunning(), 1000L);
        } finally {
            conductor.deactivate();
            manager.deactivate();
        }
    }

    @Test
    public void reactivationExplicitlyReestablishesSourceState() throws Exception {
        TimingNode node = newTimingNode("A");
        SerialExecutor lane = new SerialExecutor(4, "conductor-test", newWorker("reactivation-worker"));
        SystemConductor conductor = new SystemConductor(new TimingNodeList().add(node), null, lane);
        node.statusChangedEvent().subscribe(status -> conductor.onTimingNodeStatusChanged(node, status));

        conductor.activate();
        await(() -> conductor.nodeStateProperty(node).initialized(), 1000L);

        node.invoke(TimingNodeCommands.open(new LocationId(24)));
        await(() -> conductor.nodeStateProperty(node).currentValue() == State.OPEN, 1000L);

        conductor.deactivate();

        conductor.nodeStateProperty(node).update(State.CLOSED);
        assertEquals(State.CLOSED, conductor.nodeStateProperty(node).currentValue());

        conductor.activate();
        try {
            await(() -> conductor.nodeStateProperty(node).currentValue() == State.OPEN, 1000L);
        } finally {
            conductor.deactivate();
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

    @Test
    public void propertyRegistryRetainsOrderAndLookup() {
        TimingNode first = newTimingNode("A");
        TimingNode second = newTimingNode("B");
        SourceProperty<TimingNode, State> firstProperty =
                new SourceProperty<TimingNode, State>(first);
        SourceProperty<TimingNode, State> secondProperty =
                new SourceProperty<TimingNode, State>(second);

        PropertyRegistry properties = new PropertyRegistry();
        properties.register(firstProperty);
        properties.register(secondProperty);

        assertSame(firstProperty, properties.get(first));
        assertSame(secondProperty, properties.get(second));
        assertSame(firstProperty, properties.iterator().next());
        assertNull(properties.get(newTimingNode("C")));
    }

    @Test
    public void propertyRegistryRejectsDuplicateNode() {
        TimingNode node = newTimingNode("A");
        PropertyRegistry properties = new PropertyRegistry();
        properties.register(new SourceProperty<TimingNode, State>(node));

        try {
            properties.register(new SourceProperty<TimingNode, State>(node));
            fail("Duplicate TimingNode property accepted");
        } catch (IllegalArgumentException expected) {
            assertSame(node, properties.iterator().next().source());
        }
    }

    private TimingNode newTimingNode(String id) {
        return new TimingNode(
                new NodeId(id),
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                SystemConductorTest::now,
                ReadOnlyConfiguration.fixed(TagProcessingPolicy.defaults()),
                EventData.empty(),
                new SerialExecutor(8, "conductor-node-test", newWorker("conductor-node-test")),
                new SerialScheduledExecutor(8, "conductor-tag-test", newScheduledWorker("conductor-tag-test")));
    }

    private AntennaManager newAntennaManager(SimulatedAntenna antenna) {
        return new AntennaManager(
                new AntennaSet().add(new AntennaId("1"), antenna),
                new SerialScheduledExecutor(8, "conductor-antenna-test", newScheduledWorker("conductor-antenna-test")),
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
