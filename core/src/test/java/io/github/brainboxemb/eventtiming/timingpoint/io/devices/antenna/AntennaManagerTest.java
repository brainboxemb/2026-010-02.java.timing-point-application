package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager.AntennaState;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager.ControlException;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager.FailureReason;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AntennaManagerTest {

    @Test
    public void startupChecksHealthBeforeTimingNodeDrivenInventory() {
        ExecutorService shared = sharedExecutor();
        List<String> calls =
                Collections.synchronizedList(
                        new ArrayList<String>());
        RecordingAntenna first =
                new RecordingAntenna("A", calls);
        RecordingAntenna second =
                new RecordingAntenna("B", calls);
        AntennaManager manager = new AntennaManager(
                Arrays.<Antenna>asList(first, second),
                shared,
                4,
                Duration.ofSeconds(1));

        try {
            manager.start();
            assertEquals(
                    AntennaManager.State.RUNNING,
                    manager.state());
            assertFalse(first.inventoryRunning());
            assertFalse(second.inventoryRunning());

            manager.setOperational(true);
            assertTrue(first.inventoryRunning());
            assertTrue(second.inventoryRunning());

            manager.setOperational(false);
            assertFalse(first.inventoryRunning());
            assertFalse(second.inventoryRunning());

            manager.close();
            assertEquals(
                    AntennaManager.State.STOPPED,
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
                            "B.close",
                            "A.close"),
                    calls);
        } finally {
            shared.shutdownNow();
        }
    }

    @Test
    public void oneProbeFailureLeavesHealthyAntennaOperational() {
        ExecutorService shared = sharedExecutor();
        List<String> calls =
                Collections.synchronizedList(
                        new ArrayList<String>());
        RecordingAntenna healthy =
                new RecordingAntenna("healthy", calls);
        RecordingAntenna failed =
                new FailingProbeAntenna("failed", calls);
        AntennaManager manager = new AntennaManager(
                Arrays.<Antenna>asList(healthy, failed),
                shared,
                4,
                Duration.ofSeconds(1));

        try {
            manager.start();

            assertEquals(
                    AntennaManager.State.DEGRADED,
                    manager.state());
            assertEquals(
                    AntennaState.READY,
                    manager.status(healthy).state());
            assertEquals(
                    AntennaState.ERROR,
                    manager.status(failed).state());

            manager.setOperational(true);

            assertTrue(healthy.inventoryRunning());
            assertFalse(failed.inventoryRunning());
            assertEquals(
                    AntennaManager.State.DEGRADED,
                    manager.state());
        } finally {
            manager.close();
            shared.shutdownNow();
        }
    }

    @Test
    public void reportsSharedExecutorRejectionAsOverload() {
        ExecutorService shared = sharedExecutor();
        shared.shutdownNow();
        AntennaManager manager = new AntennaManager(
                Collections.<Antenna>singletonList(
                        new RecordingAntenna(
                                "A",
                                Collections.synchronizedList(
                                        new ArrayList<String>()))),
                shared,
                1,
                Duration.ofSeconds(1));

        try {
            manager.start();
            fail("expected shared-I/O overload");
        } catch (ControlException expected) {
            assertEquals(
                    FailureReason.OVERLOADED,
                    expected.reason());
            assertEquals(
                    AntennaManager.State.FAILED,
                    manager.state());
        }
    }

    @Test
    public void timesOutAndCancelsBlockingProviderControl() {
        ExecutorService shared = sharedExecutor();
        Antenna blocking = new BlockingProbeAntenna();
        AntennaManager manager = new AntennaManager(
                Collections.singletonList(blocking),
                shared,
                1,
                Duration.ofMillis(25));

        try {
            try {
                manager.start();
                fail("expected control timeout");
            } catch (ControlException expected) {
                assertEquals(
                        FailureReason.TIMEOUT,
                        expected.reason());
                assertEquals(
                        AntennaManager.State.FAILED,
                        manager.state());
            }
        } finally {
            try {
                manager.close();
            } catch (RuntimeException ignored) {
                // Timeout path already verifies cancellation/failure reporting.
            }
            shared.shutdownNow();
        }
    }

    private static ExecutorService sharedExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(4),
                runnable ->
                        new Thread(
                                runnable,
                                "antenna-manager-test-io"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static class RecordingAntenna implements Antenna {
        private final String name;
        private final List<String> calls;
        private final Event<TagObservation> observations =
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
        public EventSource<TagObservation> observations() {
            return observations;
        }

        @Override
        public void close() {
            calls.add(name + ".close");
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
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "probe interrupted",
                        ex);
            }
        }
    }
}
