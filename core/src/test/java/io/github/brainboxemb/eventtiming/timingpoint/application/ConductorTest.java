package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaInstallation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
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
import static org.junit.Assert.assertTrue;

public class ConductorTest {
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
        TimingNode node = newTimingNode();
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
        Conductor conductor =
                new Conductor(
                        node,
                        manager,
                        lane);

        conductor.activate();

        CountDownLatch blockerStarted =
                new CountDownLatch(1);
        CountDownLatch releaseBlocker =
                new CountDownLatch(1);

        try {
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
                conductor.timingNodeStateProperty()
                        .signalChanged();
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
        }
    }

    @Test
    public void statePropertyReadsAuthoritativeCurrentState()
            throws Exception {
        TimingNode node = newTimingNode();
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = newAntennaManager(antenna);
        SerialExecutor lane =
                new SerialExecutor(
                        4,
                        "conductor-test",
                        newWorker(
                                "conductor-test-worker"));
        Conductor conductor =
                new Conductor(
                        node,
                        manager,
                        lane);

        conductor.activate();

        try {
            node.invoke(
                    TimingNodeCommands.open(
                            new LocationId(24)));

            /*
             * A source event only invalidates the property. The property then
             * rereads the authoritative current OPEN state and emits its own
             * changedEvent, which makes Conductor enable inventory.
             */
            conductor.timingNodeStateProperty()
                    .signalChanged();

            await(
                    antenna::inventoryRunning,
                    1000L);
            assertTrue(
                    antenna.inventoryRunning());
        } finally {
            conductor.deactivate();
        }
    }

    private TimingNode newTimingNode() {
        return new TimingNode(
                new NodeId("TN-01"),
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                ConductorTest::now,
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
                Collections.singletonList(
                        AntennaInstallation.direct(
                                new AntennaId("ANT1"),
                                antenna)),
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

    private static TimingTimestamp now() {
        return TimingTimestamp.parse(
                "2026-10-06T09:00:00.000000000Z");
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
            // Conductor tests exercise coordination, not persistence.
        }
    }
}
