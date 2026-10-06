package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInfo;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.SimulatedPowerDevice;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaOperation;
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
    public void activationStartsAsynchronousSelfTestAndAppliesPendingEnable()
            throws Exception {
        ScheduledExecutorService shared =
                sharedExecutor();
        SimulatedAntenna antenna =
                new SimulatedAntenna();
        SimulatedPowerDevice power =
                new SimulatedPowerDevice(
                        antenna);
        AntennaManager manager =
                new AntennaManager(
                        Collections.singletonList(
                                AntennaInstallation.powered(
                                        new AntennaId("1"),
                                        antenna,
                                        power,
                                        Duration.ofMillis(200))),
                        new SerialScheduledExecutor(
                                8,
                                "antenna-manager-test",
                                shared),
                        Duration.ofSeconds(1));

        try {
            manager.activate();

            awaitCondition(
                    power::powered,
                    500L);
            assertTrue(
                    manager.isBusy());
            assertFalse(
                    manager.isReady());

            assertTrue(
                    manager.requestEnableInventory());
            assertFalse(
                    antenna.inventoryRunning());

            awaitCondition(
                    antenna::inventoryRunning,
                    1500L);

            assertTrue(
                    manager.isReady());
            assertTrue(
                    power.powered());
            assertTrue(
                    manager.status(
                            new AntennaId("1"))
                            .selfTestPassed());
        } finally {
            manager.deactivate();
            shared.shutdownNow();
        }
    }

    @Test
    public void activatesAgainAfterDeactivation() throws Exception {
        ScheduledExecutorService shared = sharedExecutor();
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = manager(
                Collections.<Antenna>singletonList(antenna),
                shared,
                4,
                Duration.ofSeconds(1));

        try {
            manager.activate();
            awaitCondition(manager::isReady, 1000L);
            manager.deactivate();

            assertEquals(State.INACTIVE, manager.state());

            manager.activate();
            awaitCondition(manager::isReady, 1000L);

            assertEquals(State.ACTIVE, manager.state());
            assertTrue(manager.status(new AntennaId("1")).selfTestPassed());
        } finally {
            manager.deactivate();
            shared.shutdownNow();
        }
    }

    @Test
    public void laterDisableCancelsPendingEnableBeforeSelfTestCompletes()
            throws Exception {
        ScheduledExecutorService shared =
                sharedExecutor();
        SimulatedAntenna antenna =
                new SimulatedAntenna();
        SimulatedPowerDevice power =
                new SimulatedPowerDevice(
                        antenna);
        AntennaManager manager =
                new AntennaManager(
                        Collections.singletonList(
                                AntennaInstallation.powered(
                                        new AntennaId("1"),
                                        antenna,
                                        power,
                                        Duration.ofMillis(200))),
                        new SerialScheduledExecutor(
                                8,
                                "antenna-manager-test",
                                shared),
                        Duration.ofSeconds(1));

        try {
            manager.activate();
            awaitCondition(
                    power::powered,
                    500L);

            assertTrue(
                    manager.requestEnableInventory());
            assertTrue(
                    manager.requestDisableInventory());

            awaitCondition(
                    manager::isReady,
                    1000L);

            assertFalse(
                    antenna.inventoryRunning());
            assertFalse(
                    power.powered());
        } finally {
            manager.deactivate();
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
        SimulatedPowerDevice power =
                new SimulatedPowerDevice(
                        antenna);
        Duration stabilization =
                Duration.ofMillis(200);

        AntennaManager manager =
                new AntennaManager(
                        Collections.singletonList(
                                AntennaInstallation.powered(
                                        new AntennaId("1"),
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

            awaitCondition(
                    power::powered,
                    500L);

            CountDownLatch duringSelfTestDelay =
                    new CountDownLatch(1);
            assertTrue(
                    sibling.execute(
                            duringSelfTestDelay::countDown));
            assertTrue(
                    "self-test stabilization must not occupy the shared worker",
                    duringSelfTestDelay.await(
                            75L,
                            TimeUnit.MILLISECONDS));

            awaitCondition(
                    manager::isReady,
                    1000L);
            assertFalse(
                    power.powered());

            assertTrue(
                    manager.requestEnableInventory());

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

            awaitCondition(
                    antenna::inventoryRunning,
                    1000L);
            assertTrue(
                    power.powered());

            assertTrue(
                    manager.requestDisableInventory());
            awaitCondition(
                    () -> !antenna.inventoryRunning(),
                    1000L);
            awaitCondition(
                    () -> !power.powered(),
                    1000L);
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
    public void exposesTagObservedEventByConfiguredAntennaId()
            throws Exception {
        ScheduledExecutorService shared =
                sharedExecutor();
        List<String> calls =
                Collections.synchronizedList(
                        new ArrayList<String>());
        RecordingAntenna antenna =
                new RecordingAntenna(
                        "A",
                        calls);
        AntennaManager manager =
                manager(
                        Collections.<Antenna>singletonList(
                                antenna),
                        shared,
                        4,
                        Duration.ofSeconds(1));

        AtomicReference<TagObservation> received =
                new AtomicReference<TagObservation>();
        manager.tagObservedEvent(
                        new AntennaId("1"))
                .subscribe(
                        received::set);

        try {
            manager.activate();
            awaitCondition(
                    manager::isReady,
                    1000L);
            assertTrue(
                    manager.requestEnableInventory());
            awaitCondition(
                    antenna::inventoryRunning,
                    1000L);

            TagObservation observation =
                    new TagObservation(
                            new TagId("TAG-1"),
                            -40,
                            TimingTimestamp.parse(
                                    "2026-10-05T12:00:00.000000000Z"));
            antenna.emit(
                    observation);

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
        ScheduledExecutorService shared =
                sharedExecutor();
        try {
            List<AntennaInstallation> installations =
                    Arrays.asList(
                            AntennaInstallation.direct(
                                    new AntennaId("1"),
                                    new RecordingAntenna(
                                            "A",
                                            new ArrayList<String>())),
                            AntennaInstallation.direct(
                                    new AntennaId("1"),
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
                fail(
                        "expected duplicate AntennaId rejection");
            } catch (IllegalArgumentException expected) {
                assertTrue(
                        expected.getMessage()
                                .contains(
                                        "duplicate AntennaId"));
            }
        } finally {
            shared.shutdownNow();
        }
    }

    @Test
    public void failedSelfTestLeavesManagerActiveButNotReady()
            throws Exception {
        ScheduledExecutorService shared =
                sharedExecutor();
        List<String> calls =
                Collections.synchronizedList(
                        new ArrayList<String>());
        RecordingAntenna first =
                new RecordingAntenna(
                        "first",
                        calls);
        RecordingAntenna failed =
                new FailingSelfTestAntenna(
                        "failed",
                        calls);
        AntennaManager manager =
                manager(
                        Arrays.<Antenna>asList(
                                first,
                                failed),
                        shared,
                        4,
                        Duration.ofSeconds(1));

        try {
            manager.activate();

            awaitCondition(
                    () -> !manager.isBusy(),
                    1000L);

            assertEquals(
                    State.ACTIVE,
                    manager.state());
            assertFalse(
                    manager.isReady());
            assertTrue(
                    manager.status(
                            new AntennaId("1"))
                            .selfTestPassed());
            assertFalse(
                    manager.status(
                            new AntennaId("2"))
                            .selfTestPassed());
            assertTrue(
                    manager.status(
                            new AntennaId("2"))
                            .failure() != null);

            assertTrue(
                    manager.requestEnableInventory());
            Thread.sleep(
                    50L);
            assertFalse(
                    first.inventoryRunning());
            assertFalse(
                    failed.inventoryRunning());
        } finally {
            manager.deactivate();
            shared.shutdownNow();
        }
    }

    @Test
    public void blockingSelfTestKeepsManagerBusyUntilProviderReturns()
            throws Exception {
        ScheduledExecutorService shared =
                sharedExecutor();
        BlockingSelfTestAntenna blocking =
                new BlockingSelfTestAntenna();
        AntennaManager manager =
                manager(
                        Collections.<Antenna>singletonList(
                                blocking),
                        shared,
                        1,
                        Duration.ofSeconds(1));

        try {
            manager.activate();

            assertTrue(
                    blocking.awaitEntered(
                            500L));
            assertTrue(
                    manager.isBusy());
            assertFalse(
                    manager.isReady());

            blocking.release();

            awaitCondition(
                    () -> !manager.isBusy(),
                    1000L);

            assertEquals(
                    State.ACTIVE,
                    manager.state());
            assertTrue(
                    manager.isReady());
            assertTrue(
                    manager.status(
                            new AntennaId("1"))
                            .selfTestPassed());
        } finally {
            blocking.release();
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
            Thread.sleep(
                    2L);
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
                                    Integer.toString(
                                            index + 1)),
                            antennas.get(
                                    index)));
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
        public AntennaInfo selfTest() {
            calls.add(
                    name + ".selfTest");
            return new AntennaInfo(
                    name,
                    "1");
        }

        @Override
        public void initialize() {
            calls.add(
                    name + ".initialize");
        }

        @Override
        public void startInventory() {
            calls.add(
                    name + ".start");
            running = true;
        }

        @Override
        public void stopInventory() {
            calls.add(
                    name + ".stop");
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
            tagObservedEvent.emit(
                    observation);
        }

        @Override
        public void shutdown() {
            calls.add(
                    name + ".shutdown");
            running = false;
        }
    }

    private static final class FailingSelfTestAntenna
            extends RecordingAntenna {
        private FailingSelfTestAntenna(
                String name,
                List<String> calls) {
            super(
                    name,
                    calls);
        }

        @Override
        public AntennaInfo selfTest() {
            super.selfTest();
            throw new IllegalStateException(
                    "configured self-test failure");
        }
    }

    private static final class BlockingSelfTestAntenna
            extends RecordingAntenna {
        private final CountDownLatch entered =
                new CountDownLatch(1);
        private final CountDownLatch release =
                new CountDownLatch(1);

        private BlockingSelfTestAntenna() {
            super(
                    "blocking",
                    Collections.synchronizedList(
                            new ArrayList<String>()));
        }

        @Override
        public AntennaInfo selfTest() {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread()
                        .interrupt();
                throw new IllegalStateException(
                        "self-test interrupted",
                        ex);
            }
            return new AntennaInfo(
                    "blocking",
                    "1");
        }

        private boolean awaitEntered(
                long timeoutMillis)
                throws InterruptedException {
            return entered.await(
                    timeoutMillis,
                    TimeUnit.MILLISECONDS);
        }

        private void release() {
            release.countDown();
        }
    }
}
