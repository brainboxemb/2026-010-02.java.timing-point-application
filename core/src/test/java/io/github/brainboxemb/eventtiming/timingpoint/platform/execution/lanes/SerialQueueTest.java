package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SerialQueueTest {

    @Test
    public void multipleItemsDoNotScheduleConcurrentQueueWorkers()
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountingSerialLane lane =
                new CountingSerialLane(
                        16,
                        16,
                        "serial-item-queue",
                        executor);

        List<Integer> processed =
                Collections.synchronizedList(
                        new ArrayList<Integer>());
        AtomicInteger activeProcessors = new AtomicInteger();
        AtomicInteger maxActiveProcessors = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        SerialQueue<Integer> queue =
                new SerialQueue<Integer>(
                        16,
                        2,
                        lane,
                        item -> {
                            int active = activeProcessors.incrementAndGet();
                            updateMaximum(maxActiveProcessors, active);
                            try {
                                if (item.intValue() == 1) {
                                    firstStarted.countDown();
                                    await(releaseFirst);
                                }
                                processed.add(item);
                            } finally {
                                activeProcessors.decrementAndGet();
                            }
                        });

        lane.start();
        queue.start();
        try {
            assertEquals(SerialQueue.OfferResult.ACCEPTED, queue.offer(Integer.valueOf(1)));
            assertTrue(firstStarted.await(1L, TimeUnit.SECONDS));

            assertEquals(SerialQueue.OfferResult.ACCEPTED, queue.offer(Integer.valueOf(2)));
            assertEquals(SerialQueue.OfferResult.ACCEPTED, queue.offer(Integer.valueOf(3)));
            assertEquals(SerialQueue.OfferResult.ACCEPTED, queue.offer(Integer.valueOf(4)));
            assertEquals(SerialQueue.OfferResult.ACCEPTED, queue.offer(Integer.valueOf(5)));

            assertEquals(
                    "items queued while the worker is active must not schedule another worker",
                    1,
                    lane.executeCalls.get());

            releaseFirst.countDown();
            queue.close();

            assertEquals(Arrays.asList(1, 2, 3, 4, 5), processed);
            assertEquals(1, maxActiveProcessors.get());
            assertEquals(
                    "maxItemsPerRun=2 requires three worker runs for five items",
                    3,
                    lane.executeCalls.get());
        } finally {
            releaseFirst.countDown();
            queue.close();
            lane.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void rejectedWorkerScheduleDoesNotLeaveStrandedItems()
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RunnableSerialLane lane =
                new RunnableSerialLane(
                        1,
                        1,
                        "serial-item-queue-rejection",
                        executor);
        SerialQueue<Integer> queue =
                new SerialQueue<Integer>(
                        4,
                        2,
                        lane,
                        ignored -> { });

        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        lane.start();
        queue.start();
        try {
            lane.execute(
                    () -> {
                        blockerStarted.countDown();
                        await(releaseBlocker);
                    });
            assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

            // Occupy the one waiting slot in RunnableSerialLane itself.
            lane.execute(() -> { });

            try {
                queue.offer(Integer.valueOf(1));
                fail("expected queue-worker scheduling rejection");
            } catch (RejectedExecutionException expected) {
                // The item must not remain accepted without a scheduled worker.
            }

            assertEquals(0, queue.size());
            assertEquals(
                    SerialQueue.OfferResult.NOT_RUNNING,
                    queue.offer(Integer.valueOf(2)));
        } finally {
            releaseBlocker.countDown();
            queue.close();
            lane.close();
            executor.shutdownNow();
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting test", ex);
        }
    }

    private static final class CountingSerialLane extends RunnableSerialLane {
        private final AtomicInteger executeCalls = new AtomicInteger();

        private CountingSerialLane(
                int capacity,
                int maxItemsPerRun,
                String laneName,
                ExecutorService executor) {
            super(capacity, maxItemsPerRun, laneName, executor);
        }

        @Override
        public void execute(Runnable work) {
            executeCalls.incrementAndGet();
            super.execute(work);
        }
    }
}
