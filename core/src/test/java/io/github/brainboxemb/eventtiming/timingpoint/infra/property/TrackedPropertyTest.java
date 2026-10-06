package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TrackedPropertyTest {

    @Test
    public void initializesTracksChangesAndIgnoresUnchangedValues()
            throws Exception {
        ExecutorService worker =
                Executors.newSingleThreadExecutor();
        SerialExecutor lane =
                new SerialExecutor(
                        4,
                        "tracked-property-test",
                        worker);
        AtomicReference<String> source =
                new AtomicReference<String>("A");
        AtomicInteger changes =
                new AtomicInteger();

        TrackedProperty<String> property =
                new TrackedProperty<String>(
                        "test.value",
                        lane,
                        source::get);
        property.onChange(
                ignored -> changes.incrementAndGet());

        try {
            lane.start();
            property.initialize();

            assertEquals(
                    "A",
                    property.currentValue());
            assertEquals(
                    1,
                    changes.get());

            assertTrue(
                    property.signalChanged());
            awaitCompleted(
                    lane,
                    2L);
            assertEquals(
                    "unchanged source must not publish another change",
                    1,
                    changes.get());

            source.set("B");
            assertTrue(
                    property.signalChanged());
            await(
                    () -> "B".equals(
                            property.currentValue()),
                    1000L);

            assertEquals(
                    2,
                    changes.get());
        } finally {
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void coalescesSignalsWhileRefreshIsWaitingOnLane()
            throws Exception {
        ExecutorService worker =
                Executors.newSingleThreadExecutor();
        SerialExecutor lane =
                new SerialExecutor(
                        1,
                        "tracked-property-test",
                        worker);
        AtomicReference<String> source =
                new AtomicReference<String>("A");
        TrackedProperty<String> property =
                new TrackedProperty<String>(
                        "test.value",
                        lane,
                        source::get);

        CountDownLatch blockerStarted =
                new CountDownLatch(1);
        CountDownLatch releaseBlocker =
                new CountDownLatch(1);

        try {
            lane.start();
            property.initialize();

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

            source.set("B");
            for (int index = 0;
                    index < 20;
                    index++) {
                assertTrue(
                        property.signalChanged());
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

            releaseBlocker.countDown();
            await(
                    () -> "B".equals(
                            property.currentValue()),
                    1000L);
        } finally {
            releaseBlocker.countDown();
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void reportsWhenRefreshCannotEnterFullLane()
            throws Exception {
        ExecutorService worker =
                Executors.newSingleThreadExecutor();
        SerialExecutor lane =
                new SerialExecutor(
                        1,
                        "tracked-property-test",
                        worker);
        AtomicReference<String> source =
                new AtomicReference<String>("A");
        TrackedProperty<String> property =
                new TrackedProperty<String>(
                        "test.value",
                        lane,
                        source::get);

        CountDownLatch blockerStarted =
                new CountDownLatch(1);
        CountDownLatch releaseBlocker =
                new CountDownLatch(1);

        try {
            lane.start();
            property.initialize();

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

            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    lane.offer(
                            () -> {
                                // Occupies the only queued slot.
                            }));

            source.set("B");
            assertFalse(
                    property.signalChanged());
            assertEquals(
                    1L,
                    lane.metrics()
                            .snapshot()
                            .fullCount());

            releaseBlocker.countDown();
            awaitCompleted(
                    lane,
                    3L);

            assertTrue(
                    property.signalChanged());
            await(
                    () -> "B".equals(
                            property.currentValue()),
                    1000L);
        } finally {
            releaseBlocker.countDown();
            lane.close();
            worker.shutdownNow();
        }
    }

    private static void awaitCompleted(
            SerialExecutor lane,
            long count)
            throws Exception {
        await(
                () -> lane.metrics()
                        .snapshot()
                        .completedCount() >= count,
                1000L);
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
}
