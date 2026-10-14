package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SerialLaneTest {
    private final List<ExecutorService> ownedWorkers =
            new ArrayList<ExecutorService>();

    /** Stops physical test workers that are deliberately owned by this test fixture. */
    @After
    public void stopOwnedWorkers() {
        for (ExecutorService worker : ownedWorkers) {
            worker.shutdownNow();
        }
    }

    /** Creates one bounded SerialLane on a dedicated test worker. */
    private SerialLane newLane(
            int capacity,
            String threadName) {
        ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        runnable -> new Thread(
                                runnable,
                                threadName));
        ownedWorkers.add(worker);
        return new SerialLane(
                capacity,
                threadName,
                worker);
    }

    @Test
    public void rejectsWorkBeforeStartAndAfterStop() {
        SerialLane executor = newLane(1, "serial-executor-test");

        expectRejected(() -> executor.execute(() -> { }));

        executor.start();
        executor.close();

        assertEquals(SerialLane.State.STOPPED, executor.state());
        expectRejected(() -> executor.execute(() -> { }));
    }

    @Test
    public void restartsAfterCleanStop() throws Exception {
        SerialLane executor = newLane(2, "serial-executor-restart-test");
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch second = new CountDownLatch(1);

        executor.start();
        executor.execute(first::countDown);
        assertTrue(first.await(1, TimeUnit.SECONDS));
        executor.close();

        executor.start();
        executor.execute(second::countDown);
        assertTrue(second.await(1, TimeUnit.SECONDS));
        executor.close();

        assertEquals(SerialLane.State.STOPPED, executor.state());
    }

    @Test
    public void processesQueuedWorkInFifoOrder() throws Exception {
        SerialLane executor = newLane(4, "serial-executor-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());

        executor.start();
        try {
            Future<Integer> first = executor.submit(() -> {
                firstStarted.countDown();
                releaseFirst.await();
                order.add(1);
                return 10;
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            Future<Integer> second = executor.submit(() -> {
                order.add(2);
                return 20;
            });
            releaseFirst.countDown();

            assertEquals(Integer.valueOf(10), first.get(1, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(20), second.get(1, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(1, 2), order);
        } finally {
            releaseFirst.countDown();
            executor.close();
        }
    }

    @Test
    public void reportsFullWhileCapacityIsOccupied() throws Exception {
        SerialLane executor = newLane(1, "serial-executor-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        executor.start();
        try {
            executor.submit(() -> {
                firstStarted.countDown();
                releaseFirst.await();
                return null;
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            executor.execute(() -> { });
            expectRejected(() -> executor.execute(() -> { }));
            assertTrue(executor.metrics().snapshot().queueHighWaterMark() >= 1);
        } finally {
            releaseFirst.countDown();
            executor.close();
        }
    }

    @Test
    public void closeDrainsAlreadyQueuedWork() throws Exception {
        SerialLane executor = newLane(2, "serial-executor-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        executor.start();
        executor.execute(() -> {
            firstStarted.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        executor.execute(secondDone::countDown);

        Thread closer = new Thread(executor::close);
        closer.start();

        assertFalse(secondDone.await(50, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();

        closer.join(1000);
        assertFalse(closer.isAlive());
        assertTrue(secondDone.await(1, TimeUnit.SECONDS));
        assertEquals(SerialLane.State.STOPPED, executor.state());
    }

    @Test
    public void ordinaryTaskFailureDoesNotStopLaterWork() throws Exception {
        SerialLane executor = newLane(2, "serial-executor-test");
        executor.start();
        try {
            Future<Integer> failing = executor.submit(() -> {
                throw new IllegalStateException("expected");
            });
            Future<Integer> next = executor.submit(() -> 42);

            try {
                failing.get(1, TimeUnit.SECONDS);
                fail("expected ExecutionException");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }

            assertEquals(Integer.valueOf(42), next.get(1, TimeUnit.SECONDS));
            assertEquals(SerialLane.State.RUNNING, executor.state());
        } finally {
            executor.close();
        }
    }

    @Test
    public void recordsLowAllocationRuntimeCountersAndDurations() throws Exception {
        SerialLane executor = newLane(1, "serial-executor-metrics-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch queuedDone = new CountDownLatch(1);

        expectRejected(() -> executor.execute(() -> { }));

        executor.start();
        try {
            executor.execute(() -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            executor.execute(queuedDone::countDown);
            expectRejected(() -> executor.execute(() -> { }));

            Thread.sleep(10L);
            releaseFirst.countDown();
            assertTrue(queuedDone.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.close();
        }

        SerialLaneMetrics.Snapshot metrics =
                executor.metrics().snapshot();

        assertEquals(2L, metrics.queuedCount());
        assertEquals(1L, metrics.fullCount());
        assertEquals(1L, metrics.notRunningCount());
        assertEquals(2L, metrics.completedCount());
        assertTrue(metrics.queueHighWaterMark() >= 1);
        assertTrue(metrics.maxQueueWaitNanos() > 0L);
        assertTrue(metrics.totalQueueWaitNanos() >= metrics.maxQueueWaitNanos());
        assertTrue(metrics.maxExecutionNanos() > 0L);
        assertTrue(metrics.totalExecutionNanos() >= metrics.maxExecutionNanos());
        assertTrue(metrics.workerThreadCpuTimeNanos() >= -1L);
    }


    @Test
    public void sharedLanesKeepIndependentFifoQueuesAndShareOneWorker()
            throws Exception {
        ThreadPoolExecutor shared = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                runnable -> new Thread(
                        runnable,
                        "shared-node-worker"));

        SerialLane first =
                new SerialLane(
                        4,
                        "node-A",
                        shared);
        SerialLane second =
                new SerialLane(
                        4,
                        "node-B",
                        shared);

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(3);
        List<String> order =
                Collections.synchronizedList(
                        new ArrayList<String>());
        List<String> workerNames =
                Collections.synchronizedList(
                        new ArrayList<String>());

        first.start();
        second.start();
        try {
            first.execute(() -> {
                workerNames.add(
                        Thread.currentThread().getName());
                order.add("A1");
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                allDone.countDown();
            });
            assertTrue(
                    firstStarted.await(
                            1,
                            TimeUnit.SECONDS));

            /*
             * A2 stays in lane A's private queue. B1 schedules its own drain
             * token directly on the shared role executor. When A1 completes,
             * A's next drain token is appended behind B's already waiting token.
             */
            first.execute(() -> {
                workerNames.add(
                        Thread.currentThread().getName());
                order.add("A2");
                allDone.countDown();
            });
            second.execute(() -> {
                workerNames.add(
                        Thread.currentThread().getName());
                order.add("B1");
                allDone.countDown();
            });

            releaseFirst.countDown();
            assertTrue(
                    allDone.await(
                            1,
                            TimeUnit.SECONDS));

            assertEquals(
                    Arrays.asList("A1", "B1", "A2"),
                    order);
            assertEquals(
                    Arrays.asList(
                            "shared-node-worker",
                            "shared-node-worker",
                            "shared-node-worker"),
                    workerNames);
        } finally {
            releaseFirst.countDown();
            first.close();
            second.close();
            shared.shutdownNow();
        }
    }

    @Test
    public void closingSharedLaneDoesNotStopSiblingLane()
            throws Exception {
        ThreadPoolExecutor shared = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        SerialLane first =
                new SerialLane(
                        2,
                        "node-A",
                        shared);
        SerialLane second =
                new SerialLane(
                        2,
                        "node-B",
                        shared);
        CountDownLatch secondDone = new CountDownLatch(1);

        first.start();
        second.start();
        try {
            first.close();

            assertEquals(
                    SerialLane.State.STOPPED,
                    first.state());
            assertFalse(shared.isShutdown());
            second.execute(secondDone::countDown);
            assertTrue(
                    secondDone.await(
                            1,
                            TimeUnit.SECONDS));
        } finally {
            second.close();
            shared.shutdownNow();
        }
    }


    @Test
    public void fatalFailureInSharedLaneDoesNotStopSiblingLane()
            throws Exception {
        ThreadPoolExecutor shared = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                runnable -> new Thread(
                        runnable,
                        "shared-node-worker"));

        SerialLane failing =
                new SerialLane(
                        2,
                        "node-A",
                        shared);
        SerialLane healthy =
                new SerialLane(
                        2,
                        "node-B",
                        shared);
        CountDownLatch healthyDone =
                new CountDownLatch(1);

        failing.start();
        healthy.start();
        try {
            failing.execute(() -> {
                throw new AssertionError("expected lane failure");
            });
            healthy.execute(healthyDone::countDown);

            assertTrue(
                    healthyDone.await(
                            1,
                            TimeUnit.SECONDS));

            long deadline =
                    System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(1);
            while (failing.state()
                    != SerialLane.State.FAILED
                    && System.nanoTime() < deadline) {
                Thread.yield();
            }

            assertEquals(
                    SerialLane.State.FAILED,
                    failing.state());
            assertTrue(
                    failing.failure()
                            instanceof AssertionError);
            assertEquals(
                    SerialLane.State.RUNNING,
                    healthy.state());
            assertFalse(shared.isShutdown());
        } finally {
            failing.close();
            healthy.close();
            shared.shutdownNow();
        }
    }

    @Test
    public void fatalErrorFaultsWorkerAndCancelsQueuedWork() throws Exception {
        SerialLane executor = newLane(2, "serial-executor-test");
        CountDownLatch fatalStarted = new CountDownLatch(1);
        CountDownLatch releaseFatal = new CountDownLatch(1);
        executor.start();

        Future<Void> fatal = executor.submit(() -> {
            fatalStarted.countDown();
            releaseFatal.await();
            throw new AssertionError("fatal");
        });
        assertTrue(fatalStarted.await(1, TimeUnit.SECONDS));
        Future<Integer> queued = executor.submit(() -> 42);
        releaseFatal.countDown();

        try {
            fatal.get(1, TimeUnit.SECONDS);
            fail("expected ExecutionException");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof AssertionError);
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (executor.state() != SerialLane.State.FAILED && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(SerialLane.State.FAILED, executor.state());
        assertTrue(executor.failure() instanceof AssertionError);

        try {
            queued.get();
            fail("expected queued work to be cancelled");
        } catch (CancellationException expected) {
            // Expected after executor fault.
        }
    }
    /** Verifies that standard executor rejection is reported immediately to the caller. */
    private static void expectRejected(Runnable action) {
        try {
            action.run();
            fail("expected RejectedExecutionException");
        } catch (RejectedExecutionException expected) {
            // Expected when the bounded SerialLane cannot accept more work.
        }
    }

}
