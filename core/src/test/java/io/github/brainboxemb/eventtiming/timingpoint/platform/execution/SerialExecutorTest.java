package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SerialExecutorTest {
    @Test
    public void rejectsWorkBeforeStartAndAfterStop() {
        SerialExecutor executor = new SerialExecutor(1, "serial-executor-test");

        assertEquals(
                SerialExecutor.AdmissionResult.NOT_RUNNING,
                executor.offer(() -> { }));

        executor.start();
        executor.close();

        assertEquals(SerialExecutor.State.STOPPED, executor.state());
        assertEquals(
                SerialExecutor.AdmissionResult.NOT_RUNNING,
                executor.offer(() -> { }));
    }

    @Test
    public void processesAcceptedWorkInFifoOrder() throws Exception {
        SerialExecutor executor = new SerialExecutor(4, "serial-executor-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());

        executor.start();
        try {
            SerialExecutor.SubmitResult<Integer> first = executor.submit(() -> {
                firstStarted.countDown();
                releaseFirst.await();
                order.add(1);
                return 10;
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            SerialExecutor.SubmitResult<Integer> second = executor.submit(() -> {
                order.add(2);
                return 20;
            });
            releaseFirst.countDown();

            assertEquals(Integer.valueOf(10), first.futureResult().get(1, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(20), second.futureResult().get(1, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(1, 2), order);
        } finally {
            releaseFirst.countDown();
            executor.close();
        }
    }

    @Test
    public void reportsFullWhileCapacityIsOccupied() throws Exception {
        SerialExecutor executor = new SerialExecutor(1, "serial-executor-test");
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

            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    executor.offer(() -> { }));
            assertEquals(
                    SerialExecutor.AdmissionResult.FULL,
                    executor.offer(() -> { }));
            assertTrue(executor.metrics().snapshot().queueHighWaterMark() >= 1);
        } finally {
            releaseFirst.countDown();
            executor.close();
        }
    }

    @Test
    public void closeDrainsAlreadyAcceptedWork() throws Exception {
        SerialExecutor executor = new SerialExecutor(2, "serial-executor-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        executor.start();
        executor.offer(() -> {
            firstStarted.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        executor.offer(secondDone::countDown);

        Thread closer = new Thread(executor::close);
        closer.start();

        assertFalse(secondDone.await(50, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();

        closer.join(1000);
        assertFalse(closer.isAlive());
        assertTrue(secondDone.await(1, TimeUnit.SECONDS));
        assertEquals(SerialExecutor.State.STOPPED, executor.state());
    }

    @Test
    public void ordinaryTaskFailureDoesNotStopLaterWork() throws Exception {
        SerialExecutor executor = new SerialExecutor(2, "serial-executor-test");
        executor.start();
        try {
            SerialExecutor.SubmitResult<Integer> failing = executor.submit(() -> {
                throw new IllegalStateException("expected");
            });
            SerialExecutor.SubmitResult<Integer> next = executor.submit(() -> 42);

            try {
                failing.futureResult().get(1, TimeUnit.SECONDS);
                fail("expected ExecutionException");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }

            assertEquals(Integer.valueOf(42), next.futureResult().get(1, TimeUnit.SECONDS));
            assertEquals(SerialExecutor.State.RUNNING, executor.state());
        } finally {
            executor.close();
        }
    }

    @Test
    public void recordsLowAllocationRuntimeCountersAndDurations() throws Exception {
        SerialExecutor executor = new SerialExecutor(1, "serial-executor-metrics-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch queuedDone = new CountDownLatch(1);

        assertEquals(
                SerialExecutor.AdmissionResult.NOT_RUNNING,
                executor.offer(() -> { }));

        executor.start();
        try {
            executor.offer(() -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    executor.offer(queuedDone::countDown));
            assertEquals(
                    SerialExecutor.AdmissionResult.FULL,
                    executor.offer(() -> { }));

            Thread.sleep(10L);
            releaseFirst.countDown();
            assertTrue(queuedDone.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.close();
        }

        SerialExecutor.Metrics.Snapshot metrics =
                executor.metrics().snapshot();

        assertEquals(2L, metrics.acceptedCount());
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
    public void fatalErrorFaultsWorkerAndCancelsQueuedWork() throws Exception {
        SerialExecutor executor = new SerialExecutor(2, "serial-executor-test");
        CountDownLatch fatalStarted = new CountDownLatch(1);
        CountDownLatch releaseFatal = new CountDownLatch(1);
        executor.start();

        SerialExecutor.SubmitResult<Void> fatal = executor.submit(() -> {
            fatalStarted.countDown();
            releaseFatal.await();
            throw new AssertionError("fatal");
        });
        assertTrue(fatalStarted.await(1, TimeUnit.SECONDS));
        SerialExecutor.SubmitResult<Integer> queued = executor.submit(() -> 42);
        releaseFatal.countDown();

        try {
            fatal.futureResult().get(1, TimeUnit.SECONDS);
            fail("expected ExecutionException");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof AssertionError);
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (executor.state() != SerialExecutor.State.FAILED && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(SerialExecutor.State.FAILED, executor.state());
        assertTrue(executor.failure() instanceof AssertionError);

        try {
            queued.futureResult().get();
            fail("expected queued work to be cancelled");
        } catch (CancellationException expected) {
            // Expected after executor fault.
        }
    }
}
