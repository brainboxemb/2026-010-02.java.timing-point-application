package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ExecutionMetrics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SerialLaneTest {
    private final List<ExecutorService> ownedWorkers =
            new ArrayList<ExecutorService>();

    @After
    public void stopOwnedWorkers() {
        for (ExecutorService worker : ownedWorkers) {
            worker.shutdownNow();
        }
    }

    private <T> SerialLane<T> newLane(
            int capacity,
            int maxItemsPerRun,
            String threadName,
            Consumer<T> processor) {

        ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        runnable -> new Thread(runnable, threadName));
        ownedWorkers.add(worker);
        return new SerialLane<T>(
                capacity,
                maxItemsPerRun,
                threadName,
                worker,
                processor);
    }

    @Test
    public void rejectsItemsBeforeStartAndAfterStop() {
        SerialLane<Integer> lane =
                newLane(1, 1, "typed-lane-state", ignored -> { });

        assertFalse(lane.isRunning());
        assertEquals(SerialLane.OfferResult.NOT_RUNNING, lane.offer(1));

        lane.start();
        assertTrue(lane.isRunning());
        lane.close();

        assertFalse(lane.isRunning());
        assertEquals(SerialLane.OfferResult.NOT_RUNNING, lane.offer(2));
    }

    @Test
    public void restartsAfterCleanStop() throws Exception {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);
        SerialLane<Integer> lane =
                newLane(
                        2,
                        2,
                        "typed-lane-restart",
                        item -> {
                            if (item.intValue() == 1) {
                                first.countDown();
                            } else {
                                second.countDown();
                            }
                        });

        lane.start();
        assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(1));
        assertTrue(first.await(1L, TimeUnit.SECONDS));
        lane.close();

        lane.start();
        assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(2));
        assertTrue(second.await(1L, TimeUnit.SECONDS));
        lane.close();

        assertFalse(lane.isRunning());
    }

    @Test
    public void keepsItemsInFifoOrder() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(3);
        List<Integer> order =
                Collections.synchronizedList(new ArrayList<Integer>());

        SerialLane<Integer> lane =
                newLane(
                        4,
                        4,
                        "typed-lane-fifo",
                        item -> {
                            if (item.intValue() == 1) {
                                firstStarted.countDown();
                                await(releaseFirst);
                            }
                            order.add(item);
                            allDone.countDown();
                        });

        lane.start();
        try {
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(1));
            assertTrue(firstStarted.await(1L, TimeUnit.SECONDS));
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(2));
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(3));
            releaseFirst.countDown();

            assertTrue(allDone.await(1L, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(1, 2, 3), order);
        } finally {
            releaseFirst.countDown();
            lane.close();
        }
    }

    @Test
    public void reportsFullWhenWaitingQueueReachesCapacity() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        SerialLane<Integer> lane =
                newLane(
                        1,
                        1,
                        "typed-lane-capacity",
                        item -> {
                            firstStarted.countDown();
                            await(releaseFirst);
                        });

        lane.start();
        try {
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(1));
            assertTrue(firstStarted.await(1L, TimeUnit.SECONDS));
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(2));
            assertEquals(SerialLane.OfferResult.FULL, lane.offer(3));
            assertTrue(lane.metrics().snapshot().queueHighWaterMark() >= 1);
        } finally {
            releaseFirst.countDown();
            lane.close();
        }
    }

    @Test
    public void closeDrainsAlreadyAcceptedItems() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        SerialLane<Integer> lane =
                newLane(
                        2,
                        2,
                        "typed-lane-close",
                        item -> {
                            if (item.intValue() == 1) {
                                firstStarted.countDown();
                                await(releaseFirst);
                            } else {
                                secondDone.countDown();
                            }
                        });

        lane.start();
        assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(1));
        assertTrue(firstStarted.await(1L, TimeUnit.SECONDS));
        assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(2));

        Thread closer = new Thread(lane::close);
        closer.start();

        assertFalse(secondDone.await(50L, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();

        closer.join(1000L);
        assertFalse(closer.isAlive());
        assertTrue(secondDone.await(1L, TimeUnit.SECONDS));
        assertFalse(lane.isRunning());
    }

    @Test
    public void ordinaryItemFailureDoesNotStopLaterItems() throws Exception {
        CountDownLatch secondDone = new CountDownLatch(1);
        SerialLane<Integer> lane =
                newLane(
                        2,
                        2,
                        "typed-lane-failure",
                        item -> {
                            if (item.intValue() == 1) {
                                throw new IllegalStateException("expected");
                            }
                            secondDone.countDown();
                        });

        lane.start();
        try {
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(1));
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(2));
            assertTrue(secondDone.await(1L, TimeUnit.SECONDS));
            assertTrue(lane.isRunning());
        } finally {
            lane.close();
        }
    }

    @Test
    public void recordsItemQueueMetrics() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        SerialLane<Integer> lane =
                newLane(
                        1,
                        1,
                        "typed-lane-metrics",
                        item -> {
                            if (item.intValue() == 1) {
                                firstStarted.countDown();
                                await(releaseFirst);
                            } else {
                                secondDone.countDown();
                            }
                        });

        assertEquals(SerialLane.OfferResult.NOT_RUNNING, lane.offer(0));

        lane.start();
        try {
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(1));
            assertTrue(firstStarted.await(1L, TimeUnit.SECONDS));
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(2));
            assertEquals(SerialLane.OfferResult.FULL, lane.offer(3));

            Thread.sleep(10L);
            releaseFirst.countDown();
            assertTrue(secondDone.await(1L, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            lane.close();
        }

        ExecutionMetrics.Snapshot metrics = lane.metrics().snapshot();
        assertEquals(2L, metrics.queuedCount());
        assertEquals(1L, metrics.fullCount());
        assertEquals(1L, metrics.notRunningCount());
        assertEquals(2L, metrics.completedCount());
        assertTrue(metrics.queueHighWaterMark() >= 1);
        assertTrue(metrics.maxQueueWaitNanos() > 0L);
        assertTrue(metrics.maxExecutionNanos() > 0L);
    }

    @Test
    public void limitsItemsPerWorkerRun() {
        List<Runnable> scheduled = new ArrayList<Runnable>();
        List<Integer> processed = new ArrayList<Integer>();

        SerialLane<Integer> lane =
                new SerialLane<Integer>(
                        4,
                        2,
                        "typed-lane-batch",
                        scheduled::add,
                        processed::add);

        lane.start();
        try {
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(1));
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(2));
            assertEquals(SerialLane.OfferResult.ACCEPTED, lane.offer(3));
            assertEquals(1, scheduled.size());

            scheduled.remove(0).run();
            assertEquals(Arrays.asList(1, 2), processed);
            assertEquals(1, scheduled.size());

            scheduled.remove(0).run();
            assertEquals(Arrays.asList(1, 2, 3), processed);
            assertTrue(scheduled.isEmpty());
        } finally {
            lane.close();
        }
    }

    @Test
    public void sharedLanesKeepIndependentFifoAndFairWorkerTurns()
            throws Exception {

        ThreadPoolExecutor shared =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<Runnable>(),
                        runnable -> new Thread(runnable, "shared-typed-worker"));

        List<String> order =
                Collections.synchronizedList(new ArrayList<String>());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(3);

        SerialLane<String> first =
                new SerialLane<String>(
                        4,
                        4,
                        "node-A",
                        shared,
                        item -> {
                            order.add(item);
                            if ("A1".equals(item)) {
                                firstStarted.countDown();
                                await(releaseFirst);
                            }
                            allDone.countDown();
                        });
        SerialLane<String> second =
                new SerialLane<String>(
                        4,
                        4,
                        "node-B",
                        shared,
                        item -> {
                            order.add(item);
                            allDone.countDown();
                        });

        first.start();
        second.start();
        try {
            first.offer("A1");
            assertTrue(firstStarted.await(1L, TimeUnit.SECONDS));

            first.offer("A2");
            second.offer("B1");

            releaseFirst.countDown();
            assertTrue(allDone.await(1L, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("A1", "B1", "A2"), order);
        } finally {
            releaseFirst.countDown();
            first.close();
            second.close();
            shared.shutdownNow();
        }
    }

    @Test
    public void neverRunsTwoWorkersForOneLaneConcurrently()
            throws Exception {

        ExecutorService shared = Executors.newFixedThreadPool(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);

        SerialLane<Integer> lane =
                new SerialLane<Integer>(
                        8,
                        1,
                        "typed-lane-single-worker",
                        shared,
                        item -> {
                            int current = active.incrementAndGet();
                            updateMaximum(maxActive, current);
                            try {
                                if (item.intValue() == 1) {
                                    firstStarted.countDown();
                                    await(releaseFirst);
                                }
                            } finally {
                                active.decrementAndGet();
                                done.countDown();
                            }
                        });

        lane.start();
        try {
            lane.offer(1);
            assertTrue(firstStarted.await(1L, TimeUnit.SECONDS));
            lane.offer(2);
            lane.offer(3);
            releaseFirst.countDown();

            assertTrue(done.await(1L, TimeUnit.SECONDS));
            assertEquals(1, maxActive.get());
        } finally {
            releaseFirst.countDown();
            lane.close();
            shared.shutdownNow();
        }
    }

    @Test
    public void rejectedWorkerScheduleStopsAdmissionAndClearsQueue() {
        SerialLane<Integer> lane =
                new SerialLane<Integer>(
                        2,
                        2,
                        "rejected-worker",
                        ignored -> {
                            throw new RejectedExecutionException(
                                    "expected worker rejection");
                        },
                        ignored -> { });

        lane.start();
        try {
            lane.offer(1);
            fail("expected worker rejection");
        } catch (RejectedExecutionException expected) {
            assertFalse(lane.isRunning());
            assertEquals(
                    SerialLane.OfferResult.NOT_RUNNING,
                    lane.offer(2));
            assertEquals(0, lane.metrics().snapshot().queueDepth());
        } finally {
            lane.close();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting test", ex);
        }
    }

    private static void updateMaximum(AtomicInteger maximum, int candidate) {
        int current;
        do {
            current = maximum.get();
            if (candidate <= current) {
                return;
            }
        } while (!maximum.compareAndSet(current, candidate));
    }
}
