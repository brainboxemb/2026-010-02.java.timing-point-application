package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SystemConductorTest {
    private final List<ExecutorService> workers =
            new ArrayList<ExecutorService>();

    @After
    public void stopWorkers() {
        for (ExecutorService worker : workers) {
            worker.shutdownNow();
        }
    }

    @Test
    public void statePropertyCoalescesSignalsWhileRefreshIsPending()
            throws Exception {
        TimingNode node = newTimingNode("A");
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);

        ExecutorService conductorWorker =
                newWorker(
                        "conductor-blocked-test");
        SerialExecutor lane =
                new SerialExecutor(
                        1,
                        "conductor-test",
                        conductorWorker);
        SystemConductor conductor =
                new SystemConductor(
                        Collections.singletonList(node),
                        manager,
                        lane);

        manager.activate();
        conductor.activate();

        CountDownLatch blockerStarted =
                new CountDownLatch(1);
        CountDownLatch releaseBlocker =
                new CountDownLatch(1);

        try {
            /*
             * Activation schedules an initial control task. Drain that startup
             * task before filling the capacity-one lane deliberately; otherwise
             * the blocker may race its pending admission and be rejected FULL.
             */
            await(
                    () -> lane.metrics().snapshot().queueDepth() == 0,
                    1000L);

            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    lane.offer(
                            () -> {
                                blockerStarted.countDown();
                                try {
                                    releaseBlocker.await();
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                }
                            }));
            assertTrue(
                    blockerStarted.await(
                            1L,
                            TimeUnit.SECONDS));

            for (int index = 0;
                    index < 20;
                    index++) {
                conductor.signalTimingNodeStateChanged(node);
            }

            assertEquals(
                    1,
                    lane.metrics()
                            .snapshot()
                            .queueDepth());
            assertEquals(
                    0L,
                    lane.metrics()
                            .snapshot()
                            .fullCount());
        } finally {
            releaseBlocker.countDown();
            conductor.deactivate();
            manager.deactivate();
        }
    }

    @Test
    public void statePropertyReadsAuthoritativeCurrentState()
            throws Exception {
        TimingNode node = newTimingNode("A");
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        SerialExecutor lane =
                new SerialExecutor(
                        4,
                        "conductor-test",
                        newWorker(
                                "conductor-test-worker"));
        SystemConductor conductor =
                new SystemConductor(
                        Collections.singletonList(node),
                        manager,
                        lane);

        manager.activate();
        conductor.activate();

        try {
            node.invoke(
                    TimingNodeCommands.open(
                            new LocationId(24)));

            /*
             * A source event only invalidates the property. The property then
             * rereads the authoritative current OPEN state and emits its own
             * changedEvent, which makes SystemConductor enable inventory.
             */
            conductor.signalTimingNodeStateChanged(node);

            await(
                    antenna::inventoryRunning,
                    1000L);
            assertTrue(
                    antenna.inventoryRunning());
        } finally {
            conductor.deactivate();
            manager.deactivate();
        }
    }

    @Test
    public void oneOpenNodeKeepsSharedInventoryEnabled()
            throws Exception {
        TimingNode first = newTimingNode("A");
        TimingNode second = newTimingNode("B");
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        SerialExecutor lane =
                new SerialExecutor(
                        8,
                        "system-conductor-test",
                        newWorker("system-conductor-worker"));
        SystemConductor conductor =
                new SystemConductor(
                        Arrays.asList(first, second),
                        manager,
                        lane);

        manager.activate();
        conductor.activate();
        try {
            first.invoke(TimingNodeCommands.open(new LocationId(24)));
            conductor.signalTimingNodeStateChanged(first);
            await(antenna::inventoryRunning, 1000L);

            second.invoke(TimingNodeCommands.open(new LocationId(25)));
            conductor.signalTimingNodeStateChanged(second);
            await(() -> conductor.nodeStateProperty(second).currentValue()
                    == State.OPEN, 1000L);

            first.invoke(TimingNodeCommands.close());
            conductor.signalTimingNodeStateChanged(first);
            await(() -> conductor.nodeStateProperty(first).currentValue()
                    == State.CLOSED, 1000L);
            // Inventory remains required by the second OPEN node.
            assertTrue(antenna.inventoryRunning());

            second.invoke(TimingNodeCommands.close());
            conductor.signalTimingNodeStateChanged(second);
            await(() -> !antenna.inventoryRunning(), 1000L);
        } finally {
            conductor.deactivate();
            manager.deactivate();
        }
    }

    private TimingNode newTimingNode(String id) {
        return new TimingNode(
                new NodeId(id),
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                SystemConductorTest::now,
                ReadOnlyConfiguration.fixed(
                        TagProcessingPolicy.defaults()),
                EventData.empty(),
                new SerialExecutor(
                        8,
                        "conductor-node-test",
                        newWorker(
                                "conductor-node-test")),
                new SerialScheduledExecutor(
                        8,
                        "conductor-tag-test",
                        newScheduledWorker(
                                "conductor-tag-test")));
    }

    private AntennaManager newAntennaManager(
            SimulatedAntenna antenna) {
        return new AntennaManager(
                new AntennaSet().add(new AntennaId("1"), antenna),
                new SerialScheduledExecutor(
                        8,
                        "conductor-antenna-test",
                        newScheduledWorker(
                                "conductor-antenna-test")),
                Duration.ofSeconds(1));
    }

    private ExecutorService newWorker(
            String name) {
        ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        runnable ->
                                new Thread(
                                        runnable,
                                        name));
        workers.add(
                worker);
        return worker;
    }

    private ScheduledExecutorService newScheduledWorker(
            String name) {
        ScheduledExecutorService worker =
                Executors.newSingleThreadScheduledExecutor(
                        runnable ->
                                new Thread(
                                        runnable,
                                        name));
        workers.add(
                worker);
        return worker;
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
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(
                        "condition did not become true before timeout");
            }
            Thread.sleep(2L);
        }
    }

    private static Instant now() {
        return Instant.parse(
                "2026-10-06T09:00:00Z");
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
            // SystemConductor tests exercise coordination, not persistence.
        }
    }
}
