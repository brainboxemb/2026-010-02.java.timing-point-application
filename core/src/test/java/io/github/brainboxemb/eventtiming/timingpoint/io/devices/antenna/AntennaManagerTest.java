package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManagerTypes.AntennaState;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManagerTypes.ControlException;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManagerTypes.FailureReason;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
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
        AntennaManager manager = manager(
                Arrays.<Antenna>asList(first, second),
                shared,
                4,
                Duration.ofSeconds(1));

        try {
            manager.start();
            assertEquals(
                    State.RUNNING,
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
                    State.STOPPED,
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
    public void exposesTagObservedEventByConfiguredAntennaId() {
        ExecutorService shared = sharedExecutor();
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
            manager.start();
            manager.setOperational(true);

            TagObservation observation =
                    new TagObservation(
                            new DecryptedTagId("TAG-1"),
                            -40,
                            TimingTimestamp.parse(
                                    "2026-10-05T12:00:00.000000000Z"));
            antenna.emit(observation);

            assertEquals(
                    observation,
                    received.get());
        } finally {
            manager.close();
            shared.shutdownNow();
        }
    }

    @Test
    public void rejectsDuplicateConfiguredAntennaIds() {
        ExecutorService shared = sharedExecutor();
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
                        new SerialExecutor(
                                4,
                                "antenna-manager-test",
                                shared),
                        null,
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
    public void oneProbeFailureLeavesHealthyAntennaOperational() {
        ExecutorService shared = sharedExecutor();
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
            manager.start();

            assertEquals(
                    State.DEGRADED,
                    manager.state());
            assertEquals(
                    AntennaState.READY,
                    manager.status(new AntennaId("ANT1")).state());
            assertEquals(
                    AntennaState.ERROR,
                    manager.status(new AntennaId("ANT2")).state());

            manager.setOperational(true);

            assertTrue(healthy.inventoryRunning());
            assertFalse(failed.inventoryRunning());
            assertEquals(
                    State.DEGRADED,
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
        AntennaManager manager = manager(
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
                    State.FAILED,
                    manager.state());
        }
    }

    @Test
    public void timesOutAndCancelsBlockingProviderControl() {
        ExecutorService shared = sharedExecutor();
        Antenna blocking = new BlockingProbeAntenna();
        AntennaManager manager = manager(
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
                        State.FAILED,
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

    private static AntennaManager manager(
            List<Antenna> antennas,
            ExecutorService shared,
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
                new SerialExecutor(
                        capacity,
                        "antenna-manager-test",
                        shared),
                null,
                timeout);
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
