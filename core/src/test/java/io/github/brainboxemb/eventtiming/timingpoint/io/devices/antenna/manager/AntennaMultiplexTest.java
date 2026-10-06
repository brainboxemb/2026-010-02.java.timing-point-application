package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInfo;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.SimulatedPowerDevice;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AntennaMultiplexTest {

    @Test
    public void initializesEveryGroupAntennaBeforeStartingInventory()
            throws Exception {
        ScheduledExecutorService shared =
                sharedExecutor();
        List<String> calls =
                Collections.synchronizedList(
                        new ArrayList<String>());
        OrderRecordingAntenna first =
                new OrderRecordingAntenna(
                        "A",
                        calls);
        OrderRecordingAntenna second =
                new OrderRecordingAntenna(
                        "B",
                        calls);

        AntennaId firstId = new AntennaId("ANT1");
        AntennaId secondId = new AntennaId("ANT2");
        AntennaSet antennaSet = new AntennaSet()
                .add(firstId, first)
                .add(secondId, second)
                .inventoryGroup(Duration.ofSeconds(1), firstId, secondId);

        AntennaManager manager = manager(antennaSet, shared, 8, Duration.ofSeconds(1));

        try {
            manager.activate();
            await(
                    manager::isReady,
                    1000L);

            calls.clear();
            assertTrue(
                    manager.requestEnableInventory());
            await(
                    first::inventoryRunning,
                    1000L);

            assertTrue(
                    calls.indexOf("A.initialize")
                            >= 0);
            assertTrue(
                    calls.indexOf("B.initialize")
                            >= 0);
            assertTrue(
                    calls.indexOf("A.start")
                            >= 0);
            assertTrue(
                    "first inventory must start only after both antennas are initialized",
                    calls.indexOf("A.initialize")
                            < calls.indexOf("A.start"));
            assertTrue(
                    "second antenna must be initialized before first inventory starts",
                    calls.indexOf("B.initialize")
                            < calls.indexOf("A.start"));
        } finally {
            manager.deactivate();
            shared.shutdownNow();
        }
    }

    @Test
    public void alternatesAvailableGroupMembersAndPowersDownWhenInactive()
            throws Exception {
        ScheduledExecutorService shared = sharedExecutor();
        SimulatedAntenna first = new SimulatedAntenna();
        SimulatedAntenna second = new SimulatedAntenna();
        SimulatedPowerDevice firstPower =
                new SimulatedPowerDevice(first);
        SimulatedPowerDevice secondPower =
                new SimulatedPowerDevice(second);

        AntennaId firstId = new AntennaId("ANT1");
        AntennaId secondId = new AntennaId("ANT2");
        AntennaSet antennaSet = new AntennaSet()
                .addPowered(firstId, first, firstPower, Duration.ZERO)
                .addPowered(secondId, second, secondPower, Duration.ZERO)
                .inventoryGroup(Duration.ofMillis(30), firstId, secondId);

        AntennaManager manager = manager(antennaSet, shared, 8, Duration.ofSeconds(1));

        try {
            manager.activate();
            await(
                    manager::isReady,
                    1000L);

            assertFalse(firstPower.powered());
            assertFalse(secondPower.powered());
            assertTrue(
                    manager.requestEnableInventory());
            await(
                    () -> firstPower.powered()
                            && secondPower.powered(),
                    1000L);
            assertAtMostOneInventories(first, second);

            await(
                    () -> first.inventoryStartCount() > 0
                            && second.inventoryStartCount() > 0,
                    1000L);
            assertAtMostOneInventories(first, second);

            assertTrue(
                    manager.requestDisableInventory());
            await(
                    () -> !first.inventoryRunning()
                            && !second.inventoryRunning()
                            && !firstPower.powered()
                            && !secondPower.powered(),
                    1000L);
        } finally {
            manager.deactivate();
            shared.shutdownNow();
        }
    }

    @Test
    public void failedGroupMemberIsSkippedWhileAvailableMemberContinues()
            throws Exception {
        ScheduledExecutorService shared = sharedExecutor();
        SimulatedAntenna available = new SimulatedAntenna();
        SimulatedAntenna failed = new SimulatedAntenna();

        AntennaId availableId = new AntennaId("ANT1");
        AntennaId failedId = new AntennaId("ANT2");
        AntennaSet antennaSet = new AntennaSet()
                .add(availableId, available)
                .add(failedId, failed)
                .inventoryGroup(Duration.ofMillis(25), availableId, failedId);

        AntennaManager manager = manager(antennaSet, shared, 8, Duration.ofSeconds(1));

        try {
            manager.activate();
            await(
                    manager::isReady,
                    1000L);

            failed.setFailurePoint(
                    SimulatedAntenna.FailurePoint.START_INVENTORY);
            assertTrue(
                    manager.requestEnableInventory());

            await(
                    () -> manager.status(
                            new AntennaId("ANT2")).failure()
                            != null,
                    1000L);
            await(
                    available::inventoryRunning,
                    1000L);

            assertEquals(
                    State.ACTIVE,
                    manager.state());
            assertTrue(
                    available.inventoryRunning());
            assertFalse(
                    failed.inventoryRunning());
        } finally {
            manager.deactivate();
            shared.shutdownNow();
        }
    }

    @Test
    public void failedStopDoesNotStartAnotherGroupMember()
            throws Exception {
        ScheduledExecutorService shared = sharedExecutor();
        SimulatedAntenna first = new SimulatedAntenna();
        SimulatedAntenna second = new SimulatedAntenna();

        AntennaId firstId = new AntennaId("ANT1");
        AntennaId secondId = new AntennaId("ANT2");
        AntennaSet antennaSet = new AntennaSet()
                .add(firstId, first)
                .add(secondId, second)
                .inventoryGroup(Duration.ofMillis(200), firstId, secondId);

        AntennaManager manager = manager(antennaSet, shared, 8, Duration.ofSeconds(1));

        try {
            manager.activate();
            await(
                    manager::isReady,
                    1000L);
            assertTrue(
                    manager.requestEnableInventory());

            await(
                    first::inventoryRunning,
                    500L);

            first.setFailurePoint(
                    SimulatedAntenna.FailurePoint.STOP_INVENTORY);

            await(
                    () -> manager.status(
                            new AntennaId("ANT1")).failure()
                            != null,
                    1000L);

            assertTrue(
                    "failed stop means the first reader may still be inventorying",
                    first.inventoryRunning());
            assertEquals(
                    "second reader must not start when stopping the first failed",
                    0,
                    second.inventoryStartCount());
            assertAtMostOneInventories(
                    first,
                    second);

            first.clearFailure();
        } finally {
            manager.deactivate();
            shared.shutdownNow();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownInventoryGroupMember() {
        AntennaSet antennaSet = new AntennaSet()
                .add(new AntennaId("ANT1"), new SimulatedAntenna())
                .add(new AntennaId("ANT2"), new SimulatedAntenna());

        antennaSet.inventoryGroup(
                Duration.ofMillis(25),
                new AntennaId("ANT1"),
                new AntennaId("ANT3"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSingleMemberInventoryGroup() {
        AntennaId antennaId = new AntennaId("ANT3");
        new AntennaSet()
                .add(antennaId, new SimulatedAntenna())
                .inventoryGroup(Duration.ofMillis(25), antennaId);
    }

    private static final class OrderRecordingAntenna
            implements Antenna {
        private final String name;
        private final List<String> calls;
        private final Event<TagObservation> tagObserved =
                new Event<TagObservation>();
        private boolean running;

        private OrderRecordingAntenna(
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
            return tagObserved;
        }

        @Override
        public void shutdown() {
            running = false;
        }
    }

    private static void assertAtMostOneInventories(
            SimulatedAntenna first,
            SimulatedAntenna second) {
        assertFalse(
                first.inventoryRunning()
                        && second.inventoryRunning());
    }

    private static void await(
            BooleanSupplier condition,
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
            Thread.sleep(5L);
        }
    }

    private static AntennaManager manager(
            AntennaSet antennaSet,
            ScheduledExecutorService shared,
            int capacity,
            Duration timeout) {
        return new AntennaManager(
                antennaSet,
                new SerialScheduledExecutor(
                        capacity,
                        "antenna-multiplex-test",
                        shared),
                timeout);
    }

    private static ScheduledExecutorService sharedExecutor() {
        return Executors.newScheduledThreadPool(
                1,
                runnable ->
                        new Thread(
                                runnable,
                                "antenna-multiplex-test-io"));
    }
}
