package oss.relay.eventtiming.timingpoint.io.devices.antenna.manager;

import oss.relay.eventtiming.eventdata.TagId;
import oss.relay.eventtiming.timingdata.TimingTimestamp;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.model.Antenna;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.AntennaInfo;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import oss.relay.eventtiming.timingpoint.io.devices.power.SimulatedPowerDevice;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaOperation;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import oss.relay.eventtiming.timingpoint.platform.events.Event;
import oss.relay.eventtiming.timingpoint.platform.events.EventSource;
import oss.relay.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import oss.relay.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;

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

import static oss.relay.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static oss.relay.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AntennaManagerTest {

    @Test
    public void desiredInventorySetBeforeActivationIsAppliedAfterSelfTest()
            throws Exception {
        ScheduledExecutorService shared = sharedExecutor();
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = manager(
                Collections.<Antenna>singletonList(antenna),
                shared,
                4,
                Duration.ofSeconds(1));

        try {
            manager.setInventoryRequired(true);
            assertFalse(antenna.inventoryRunning());

            activate(manager);
            awaitCondition(antenna::inventoryRunning, 1000L);
            assertTrue(antenna.inventoryRunning());
        } finally {
            deactivate(manager);
            shared.shutdownNow();
        }
    }

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
                        new AntennaSet().addPowered(
                                new AntennaId("1"),
                                antenna,
                                power,
                                Duration.ofMillis(200)),
                        new SerialTaskRunner(
                                8,
                                "antenna-manager-test",
                                shared,
                                Duration.ofSeconds(1)));

        try {
            activate(manager);

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
            deactivate(manager);
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
            activate(manager);
            awaitCondition(manager::isReady, 1000L);
            deactivate(manager);

            assertEquals(State.INACTIVE, manager.state());

            activate(manager);
            awaitCondition(manager::isReady, 1000L);

            assertEquals(State.ACTIVE, manager.state());
            assertTrue(manager.status(new AntennaId("1")).selfTestPassed());
        } finally {
            deactivate(manager);
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
                        new AntennaSet().addPowered(
                                new AntennaId("1"),
                                antenna,
                                power,
                                Duration.ofMillis(200)),
                        new SerialTaskRunner(
                                8,
                                "antenna-manager-test",
                                shared,
                                Duration.ofSeconds(1)));

        try {
            activate(manager);
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
            deactivate(manager);
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
                        new AntennaSet().addPowered(
                                new AntennaId("1"),
                                antenna,
                                power,
                                stabilization),
                        new SerialTaskRunner(
                                8,
                                "antenna-manager-test",
                                shared,
                                Duration.ofSeconds(1)));

        SerialLane<Runnable> sibling =
                new SerialLane<Runnable>(
                        4,
                        4,
                        "sibling-io-test",
                        shared,
                        Runnable::run);
        sibling.start();

        try {
            activate(manager);

            awaitCondition(
                    power::powered,
                    500L);

            CountDownLatch duringSelfTestDelay =
                    new CountDownLatch(1);
            assertEquals(
                    SerialLane.OfferResult.ACCEPTED,
                    sibling.offer(
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
            assertEquals(
                    SerialLane.OfferResult.ACCEPTED,
                    sibling.offer(
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
                deactivate(manager);
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
            activate(manager);
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
                            new TagId("X0001A"),
                            -40,
                            TimingTimestamp.parse(
                                    "2026-10-05T12:00:00.000000000Z")
                                    .instant()
                                    .toEpochMilli(),
                            123L);
            antenna.emit(
                    observation);

            assertEquals(
                    observation,
                    received.get());
        } finally {
            deactivate(manager);
            shared.shutdownNow();
        }
    }

    @Test
    public void rejectsDuplicateConfiguredAntennaIds() {
        ScheduledExecutorService shared =
                sharedExecutor();
        try {
            try {
                new AntennaSet()
                        .add(
                                new AntennaId("1"),
                                new RecordingAntenna("A", new ArrayList<String>()))
                        .add(
                                new AntennaId("1"),
                                new RecordingAntenna("B", new ArrayList<String>()));
                fail("expected duplicate AntennaId rejection");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("duplicate AntennaId"));
            }
        } finally {
            shared.shutdownNow();
        }
    }

    @Test
    public void failedSelfTestDoesNotBlockLaterInventoryAttempt()
            throws Exception {
        ScheduledExecutorService shared = sharedExecutor();
        List<String> calls = Collections.synchronizedList(new ArrayList<String>());
        RecordingAntenna first = new RecordingAntenna("first", calls);
        RecordingAntenna failedSelfTest = new FailingSelfTestAntenna("failed", calls);
        AntennaManager manager = manager(
                Arrays.<Antenna>asList(first, failedSelfTest),
                shared,
                4,
                Duration.ofSeconds(1));

        try {
            activate(manager);
            awaitCondition(manager::isReady, 1000L);

            assertEquals(State.ACTIVE, manager.state());
            assertTrue(manager.status(new AntennaId("1")).selfTestPassed());
            assertFalse(manager.status(new AntennaId("2")).selfTestPassed());

            /*
             * Self-test FAIL is diagnostic only. A later inventory demand must
             * still make a fresh preparation/start attempt.
             */
            assertTrue(manager.requestEnableInventory());
            awaitCondition(first::inventoryRunning, 1000L);
            awaitCondition(failedSelfTest::inventoryRunning, 1000L);

            assertTrue(first.inventoryRunning());
            assertTrue(failedSelfTest.inventoryRunning());
        } finally {
            deactivate(manager);
            shared.shutdownNow();
        }
    }

    @Test
    public void repeatedDesiredInventoryStateDoesNotRetryFailedAttempt()
            throws Exception {
        ScheduledExecutorService shared = sharedExecutor();
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = manager(
                Collections.<Antenna>singletonList(antenna),
                shared,
                4,
                Duration.ofSeconds(1));

        try {
            activate(manager);
            awaitCondition(manager::isReady, 1000L);

            antenna.setFailurePoint(
                    SimulatedAntenna.FailurePoint.START_INVENTORY);
            manager.setInventoryRequired(true);

            awaitCondition(
                    () -> manager.status(new AntennaId("1")).failure() != null,
                    1000L);
            awaitCondition(
                    () -> !manager.isBusy(),
                    1000L);
            assertFalse(
                    antenna.inventoryRunning());

            antenna.clearFailure();

            /*
             * State-driven control may observe OPEN again after wake
             * coalescing. Repeating the same desired TRUE must not become an
             * implicit retry.
             */
            manager.setInventoryRequired(true);
            Thread.sleep(
                    50L);

            assertFalse(
                    antenna.inventoryRunning());
            assertEquals(
                    0,
                    antenna.inventoryStartCount());

            /*
             * An explicit repeated request is still a deliberate retry.
             */
            assertTrue(
                    manager.requestEnableInventory());
            awaitCondition(
                    antenna::inventoryRunning,
                    1000L);
            assertEquals(
                    1,
                    antenna.inventoryStartCount());
        } finally {
            deactivate(manager);
            shared.shutdownNow();
        }
    }

    @Test
    public void repeatedEnableRetriesAfterFailedInventoryAttempt()
            throws Exception {
        ScheduledExecutorService shared = sharedExecutor();
        SimulatedAntenna antenna = new SimulatedAntenna();
        AntennaManager manager = manager(
                Collections.<Antenna>singletonList(antenna),
                shared,
                4,
                Duration.ofSeconds(1));

        try {
            activate(manager);
            awaitCondition(manager::isReady, 1000L);

            antenna.setFailurePoint(SimulatedAntenna.FailurePoint.START_INVENTORY);
            assertTrue(manager.requestEnableInventory());

            awaitCondition(
                    () -> manager.status(new AntennaId("1")).failure() != null,
                    1000L);
            awaitCondition(() -> !manager.isBusy(), 1000L);
            assertFalse(antenna.inventoryRunning());

            antenna.clearFailure();

            /*
             * The desired value is still TRUE. This second TRUE is nevertheless
             * a new explicit request and must trigger a new attempt.
             */
            assertTrue(manager.requestEnableInventory());
            awaitCondition(antenna::inventoryRunning, 1000L);

            assertTrue(antenna.inventoryRunning());
        } finally {
            deactivate(manager);
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
            activate(manager);

            assertTrue(
                    blocking.awaitEntered(
                            500L));
            assertTrue(
                    manager.isBusy());
            assertFalse(
                    manager.isReady());

            blocking.release();

            awaitCondition(
                    manager::isReady,
                    1000L);

            assertEquals(
                    State.ACTIVE,
                    manager.state());
            assertFalse(
                    manager.isBusy());
            assertTrue(
                    manager.status(
                            new AntennaId("1"))
                            .selfTestPassed());
        } finally {
            blocking.release();
            deactivate(manager);
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
        AntennaSet antennaSet = new AntennaSet();
        for (int index = 0; index < antennas.size(); index++) {
            antennaSet.add(new AntennaId(Integer.toString(index + 1)), antennas.get(index));
        }

        return new AntennaManager(
                antennaSet,
                new SerialTaskRunner(
                        capacity,
                        "antenna-manager-test",
                        shared,
                        timeout));
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
