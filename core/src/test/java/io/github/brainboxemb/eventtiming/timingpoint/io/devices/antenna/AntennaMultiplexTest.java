package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AntennaMultiplexTest {

    @Test
    public void alternatesHealthyGroupMembersAndPowersDownWhenInactive()
            throws Exception {
        ExecutorService shared = sharedExecutor();
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();
        SimulatedAntenna first = new SimulatedAntenna();
        SimulatedAntenna second = new SimulatedAntenna();
        SimulatedAntennaPowerControl firstPower =
                new SimulatedAntennaPowerControl(first);
        SimulatedAntennaPowerControl secondPower =
                new SimulatedAntennaPowerControl(second);

        AntennaManager manager = new AntennaManager(
                Arrays.asList(
                        AntennaInstallation.powered(
                                        first,
                                        firstPower,
                                        Duration.ZERO)
                                .inInventoryGroup(
                                        "timing-rf",
                                        Duration.ofMillis(30)),
                        AntennaInstallation.powered(
                                        second,
                                        secondPower,
                                        Duration.ZERO)
                                .inInventoryGroup(
                                        "timing-rf",
                                        Duration.ofMillis(30))),
                shared,
                scheduler,
                8,
                Duration.ofSeconds(1));

        try {
            manager.start();
            assertFalse(firstPower.powered());
            assertFalse(secondPower.powered());

            manager.setOperational(true);
            assertTrue(firstPower.powered());
            assertTrue(secondPower.powered());
            assertAtMostOneInventories(first, second);

            await(
                    () -> first.inventoryStartCount() > 0
                            && second.inventoryStartCount() > 0,
                    1000L);
            assertAtMostOneInventories(first, second);

            manager.setOperational(false);
            assertFalse(first.inventoryRunning());
            assertFalse(second.inventoryRunning());
            assertFalse(firstPower.powered());
            assertFalse(secondPower.powered());
        } finally {
            manager.close();
            scheduler.shutdownNow();
            shared.shutdownNow();
        }
    }

    @Test
    public void failedGroupMemberIsSkippedWhileHealthyMemberContinues()
            throws Exception {
        ExecutorService shared = sharedExecutor();
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();
        SimulatedAntenna healthy = new SimulatedAntenna();
        SimulatedAntenna failed = new SimulatedAntenna();

        AntennaManager manager = new AntennaManager(
                Arrays.asList(
                        AntennaInstallation.direct(healthy)
                                .inInventoryGroup(
                                        "timing-rf",
                                        Duration.ofMillis(25)),
                        AntennaInstallation.direct(failed)
                                .inInventoryGroup(
                                        "timing-rf",
                                        Duration.ofMillis(25))),
                shared,
                scheduler,
                8,
                Duration.ofSeconds(1));

        try {
            manager.start();
            failed.setFailurePoint(
                    SimulatedAntenna.FailurePoint.START_INVENTORY);
            manager.setOperational(true);

            await(
                    () -> manager.status(failed).state()
                            == AntennaManager.AntennaState.ERROR,
                    1000L);
            await(healthy::inventoryRunning, 1000L);

            assertEquals(
                    AntennaManager.State.DEGRADED,
                    manager.state());
            assertTrue(healthy.inventoryRunning());
            assertFalse(failed.inventoryRunning());
        } finally {
            manager.close();
            scheduler.shutdownNow();
            shared.shutdownNow();
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

    private static ExecutorService sharedExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(8),
                runnable ->
                        new Thread(
                                runnable,
                                "antenna-multiplex-test-io"),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
