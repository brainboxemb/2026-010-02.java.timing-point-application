package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInfo;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.SimulatedAntennaPowerControl;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaHealth;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaOperation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.ControlException;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.FailureReason;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.ManagerHealth;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AntennaManagerTest {

    @Test
    public void activationIsHardwareFreeAndHealthCheckPrecedesInventory() {
        ScheduledExecutorService shared = sharedExecutor();
        List<String> calls =
                Collections.synchronizedList(
                        new ArrayList<String>());
        RecordingAntenna first =
                new RecordingAntenna("A", calls);
        RecordingAntenna second =
                new RecordingAntenna("B", calls);
        AntennaManager manager = manager(
                Arrays.<Antenna>asList(first, second),
                shared,
                4,
                Duration.ofSeconds(1));

        try {
            manager.activate();

            assertEquals(
                    State.ACTIVE,
                    manager.state());
            assertEquals(
                    ManagerHealth.UNKNOWN,
                    manager.health());
            assertTrue(
                    "activation must not touch antenna hardware",
                    calls.isEmpty());

            manager.checkHealth();

            assertEquals(
                    ManagerHealth.HEALTHY,
                    manager.health());
            assertEquals(
                    AntennaHealth.HEALTHY,
                    manager.status(new AntennaId("ANT1")).health());
            assertEquals(
                    AntennaOperation.INACTIVE,
                    manager.status(new AntennaId("ANT1")).operation());
            assertFalse(first.inventoryRunning());
            assertFalse(second.inventoryRunning());

            manager.enableInventory();
            assertTrue(first.inventoryRunning());
            assertTrue(second.inventoryRunning());

            manager.disableInventory();
            assertFalse(first.inventoryRunning());
            assertFalse(second.inventoryRunning());

            manager.deactivate();
            assertEquals(
                    State.INACTIVE,
                    manager.state());

            assertEquals(
                    Arrays.asList(
                            "A.probe",
                            "B.probe",
                            "A.initialize",
                            "A.start",
                            "B.initialize",
                            "B.start",
                            "B.stop",
                            "A.stop",
                            "B.shutdown",
                            "A.shutdown"),
                    calls);
        } finally {
            shared.shutdownNow();
        }
    }

    @Test
    public void stabilizationDelayDoesNotOccupyTheSharedIoWorker()
            throws Exception {
        ScheduledExecutorService shared =
                sharedExecutor();
        SimulatedAntenna antenna =
                new SimulatedAntenna();
        SimulatedAntennaPowerControl power =
                new SimulatedAntennaPowerControl(
                        antenna);
        Duration stabilization =
                Duration.ofMillis(200);

        AntennaManager manager =
                new AntennaManager(
                        Collections.singletonList(
                                AntennaInstallation.powered(
                                        new AntennaId("ANT1"),
                                        antenna,
                                        power,
                                        stabilization)),
                        new SerialScheduledExecutor(
                                8,
                                "antenna-manager-test",
                                shared),
                        Duration.ofSeconds(1));

        SerialScheduledExecutor sibling =
                new SerialScheduledExecutor(
                        4,
                        "sibling-io-test",
                        shared);
        sibling.start();

        try {
            manager.activate();

            Thread healthCheck =
                    new Thread(
                            manager::checkHealth,
                            "antenna-health-test");
            healthCheck.start();

            awaitCondition(
                    power::powered,
                    500L);

            CountDownLatch duringProbeDelay =
                    new CountDownLatch(1);
            assertTrue(
                    sibling.execute(
                            duringProbeDelay::countDown));
            assertTrue(
                    "probe stabilization must not occupy the shared worker",
                    duringProbeDelay.await(
                            75L,
                            TimeUnit.MILLISECONDS));

            healthCheck.join(1500L);
            assertFalse(healthCheck.isAlive());
            assertEquals(
                    State.ACTIVE,
                    manager.state());
            assertEquals(
                    ManagerHealth.HEALTHY,
                    manager.health());
            assertFalse(power.powered());

            Thread enable =
                    new Thread(
                            () -> manager.enableInventory(),
                            "antenna-enable-test");
            enable.start();

            awaitCondition(
                    power::powered,
                    500L);

            CountDownLatch duringInitializeDelay =
                    new CountDownLatch(1);
            assertTrue(
                    sibling.execute(
                            duringInitializeDelay::countDown));
            assertTrue(
                    "initialize stabilization must not occupy the shared worker",
                    duringInitializeDelay.await(
                            75L,
                            TimeUnit.MILLISECONDS));

            enable.join(1500L);
            assertFalse(enable.isAlive());
            assertTrue(antenna.inventoryRunning());
        } finally {
            try {
                manager.deactivate();
            } finally {
                sibling.close();
                shared.shutdownNow();
            }
        }
    }

    @Test
    public void exposesTagObservedEventByConfiguredAntennaId() {
        ScheduledExecutorService shared = sharedExecutor();
        List<String> calls =
                Collections.synchronizedList(
                        new ArrayList<String>());
        RecordingAntenna antenna =
                new RecordingAntenna("A", calls);
        AntennaManager manager = manager(
                Collections.<Antenna>singletonList(
                        antenna),
                shared,
                4,
                Duration.ofSeconds(1));

        AtomicReference<TagObservation> received =
                new AtomicReference<TagObservation>();
        manager.tagObservedEvent(
                        new AntennaId("ANT1"))
                .subscribe(received::set);

        try {
            manager.activate();
            manager.checkHealth();
            manager.enableInventory();

            TagObservation observation =
                    new TagObservation(
                            new TagId("TAG-1"),
                            -40,
                            TimingTimestamp.parse(
                                    "2026-10-05T12:00:00.000000000Z"));
            antenna.emit(observation);

            assertEquals(
                    observation,
                    received.get());
        } finally {
            manager.deactivate();
            shared.shutdownNow();
        }
    }

    @Test
    public void rejectsDuplicateConfiguredAntennaIds() {
        ScheduledExecutorService shared = sharedExecutor();
        try {
            List<AntennaInstallation> installations =
                    Arrays.asList(
                            AntennaInstallation.direct(
                                    new AntennaId("ANT1"),
                                    new RecordingAntenna(
                                            "A",
                                            new ArrayList<String>())),
                            AntennaInstallation.direct(
                                    new AntennaId("ANT1"),
                                    new RecordingAntenna(
                                            "B",
                                            new ArrayList<String>())));

            try {
                new AntennaManager(
                        installations,
                        new SerialScheduledExecutor(
                                4,
                                "antenna-manager-test",
                                shared),
                        Duration.ofSeconds(1));
                fail("expected duplicate AntennaId rejection");
            } catch (IllegalArgumentException expected) {
                assertTrue(
                        expected.getMessage()
                                .contains("duplicate AntennaId"));
            }
        } finally {
            shared.shutdownNow();
        }
    }

    @Test
    public void oneProbeFailureDegradesHealthButManagerRemainsActive() {
        ScheduledExecutorService shared = sharedExecutor();
        List<String> calls =
                Collections.synchronizedList(
                        new ArrayList<String>());
        RecordingAntenna healthy =
                new RecordingAntenna("healthy", calls);
        RecordingAntenna failed =
                new FailingProbeAntenna("failed", calls);
        AntennaManager manager = manager(
                Arrays.<Antenna>asList(healthy, failed),
                shared,
                4,
                Duration.ofSeconds(1));

        try {
            manager.activate();
            manager.checkHealth();

            assertEquals(
                    State.ACTIVE,
                    manager.state());
            assertEquals(
                    ManagerHealth.DEGRADED,
                    manager.health());
            assertEquals(
                    AntennaHealth.HEALTHY,
                    manager.status(new AntennaId("ANT1")).health());
            assertEquals(
                    AntennaOperation.INACTIVE,
                    manager.status(new AntennaId("ANT1")).operation());
            assertEquals(
                    AntennaHealth.FAILED,
                    manager.status(new AntennaId("ANT2")).health());
            assertEquals(
                    AntennaOperation.INACTIVE,
                    manager.status(new AntennaId("ANT2")).operation());

            manager.enableInventory();

            assertTrue(healthy.inventoryRunning());
            assertFalse(failed.inventoryRunning());
            assertEquals(
                    State.ACTIVE,
                    manager.state());
            assertEquals(
                    ManagerHealth.DEGRADED,
                    manager.health());
        } finally {
            manager.deactivate();
            shared.shutdownNow();
        }
    }

    @Test
    public void reportsSharedExecutorRejectionAsManagerFailure() {
        ScheduledExecutorService shared = sharedExecutor();
        shared.shutdownNow();
        AntennaManager manager = manager(
                Collections.<Antenna>singletonList(
                        new RecordingAntenna(
                                "A",
                                Collections.synchronizedList(
                                        new ArrayList<String>()))),
                shared,
                1,
                Duration.ofSeconds(1));

        manager.activate();

        try {
            manager.checkHealth();
            fail("expected shared-I/O overload");
        } catch (ControlException expected) {
            assertEquals(
                    FailureReason.OVERLOADED,
                    expected.reason());
            assertEquals(
                    State.FAILED,
                    manager.state());
        }
    }

    @Test
    public void healthCheckTimeoutIsContainedToAntennaAndCancelsProvider() {
        ScheduledExecutorService shared = sharedExecutor();
        BlockingProbeAntenna blocking =
                new BlockingProbeAntenna();
        AntennaManager manager = manager(
                Collections.<Antenna>singletonList(
                        blocking),
                shared,
                1,
                Duration.ofMillis(25));

        try {
            manager.activate();
            manager.checkHealth();

            assertEquals(
                    State.ACTIVE,
                    manager.state());
            assertEquals(
                    ManagerHealth.FAILED,
                    manager.health());
            assertEquals(
                    AntennaHealth.FAILED,
                    manager.status(new AntennaId("ANT1")).health());
            assertTrue(
                    "blocking provider call must be interrupted on timeout",
                    blocking.interrupted());
        } finally {
            manager.deactivate();
            shared.shutdownNow();
        }
    }

    private static void awaitCondition(
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

    private static AntennaManager manager(
            List<Antenna> antennas,
            ScheduledExecutorService shared,
            int capacity,
            Duration timeout) {
        List<AntennaInstallation> installations =
                new ArrayList<AntennaInstallation>(
                        antennas.size());
        for (int index = 0;
                index < antennas.size();
                index++) {
            installations.add(
                    AntennaInstallation.direct(
                            new AntennaId(
                                    "ANT" + (index + 1)),
                            antennas.get(index)));
        }

        return new AntennaManager(
                installations,
                new SerialScheduledExecutor(
                        capacity,
                        "antenna-manager-test",
                        shared),
                timeout);
    }

    private static ScheduledExecutorService sharedExecutor() {
        return Executors.newScheduledThreadPool(
                1,
                runnable ->
                        new Thread(
                                runnable,
                                "antenna-manager-test-io"));
    }

    private static class RecordingAntenna implements Antenna {
        private final String name;
        private final List<String> calls;
        private final Event<TagObservation> tagObservedEvent =
                new Event<TagObservation>();
        private boolean running;

        private RecordingAntenna(
                String name,
                List<String> calls) {
            this.name = name;
            this.calls = calls;
        }

        @Override
        public AntennaInfo probe() {
            calls.add(name + ".probe");
            return new AntennaInfo(name, "1");
        }

        @Override
        public void initialize() {
            calls.add(name + ".initialize");
        }

        @Override
        public void startInventory() {
            calls.add(name + ".start");
            running = true;
        }

        @Override
        public void stopInventory() {
            calls.add(name + ".stop");
            running = false;
        }

        @Override
        public boolean inventoryRunning() {
            return running;
        }

        @Override
        public EventSource<TagObservation> tagObservedEvent() {
            return tagObservedEvent;
        }

        private void emit(
                TagObservation observation) {
            tagObservedEvent.emit(observation);
        }

        @Override
        public void shutdown() {
            calls.add(name + ".shutdown");
            running = false;
        }
    }

    private static final class FailingProbeAntenna
            extends RecordingAntenna {
        private FailingProbeAntenna(
                String name,
                List<String> calls) {
            super(name, calls);
        }

        @Override
        public AntennaInfo probe() {
            super.probe();
            throw new IllegalStateException(
                    "configured probe failure");
        }
    }

    private static final class BlockingProbeAntenna
            extends RecordingAntenna {
        private volatile boolean interrupted;

        private BlockingProbeAntenna() {
            super(
                    "blocking",
                    Collections.synchronizedList(
                            new ArrayList<String>()));
        }

        @Override
        public AntennaInfo probe() {
            try {
                while (true) {
                    Thread.sleep(1000L);
                }
            } catch (InterruptedException ex) {
                interrupted = true;
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "probe interrupted",
                        ex);
            }
        }

        private boolean interrupted() {
            return interrupted;
        }
    }
}
