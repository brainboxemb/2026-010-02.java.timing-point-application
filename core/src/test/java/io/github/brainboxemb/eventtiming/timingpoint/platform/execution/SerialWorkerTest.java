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

public class SerialWorkerTest {
    @Test
    public void rejectsWorkBeforeStartAndAfterStop() {
        SerialWorker worker = new SerialWorker(1, "serial-worker-test");

        assertEquals(
                SerialWorker.AdmissionResult.NOT_RUNNING,
                worker.offer(() -> { }));

        worker.start();
        worker.close();

        assertEquals(SerialWorker.State.STOPPED, worker.state());
        assertEquals(
                SerialWorker.AdmissionResult.NOT_RUNNING,
                worker.offer(() -> { }));
    }

    @Test
    public void processesAcceptedWorkInFifoOrder() throws Exception {
        SerialWorker worker = new SerialWorker(4, "serial-worker-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());

        worker.start();
        try {
            SerialWorker.SubmitResult<Integer> first = worker.submit(() -> {
                firstStarted.countDown();
                releaseFirst.await();
                order.add(1);
                return 10;
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            SerialWorker.SubmitResult<Integer> second = worker.submit(() -> {
                order.add(2);
                return 20;
            });
            releaseFirst.countDown();

            assertEquals(Integer.valueOf(10), first.futureResult().get(1, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(20), second.futureResult().get(1, TimeUnit.SECONDS));
            assertEquals(Arrays.asList(1, 2), order);
        } finally {
            releaseFirst.countDown();
            worker.close();
        }
    }

    @Test
    public void reportsFullWhileCapacityIsOccupied() throws Exception {
        SerialWorker worker = new SerialWorker(1, "serial-worker-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        worker.start();
        try {
            worker.submit(() -> {
                firstStarted.countDown();
                releaseFirst.await();
                return null;
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            assertEquals(
                    SerialWorker.AdmissionResult.ACCEPTED,
                    worker.offer(() -> { }));
            assertEquals(
                    SerialWorker.AdmissionResult.FULL,
                    worker.offer(() -> { }));
            assertTrue(worker.highWaterMark() >= 1);
        } finally {
            releaseFirst.countDown();
            worker.close();
        }
    }

    @Test
    public void closeDrainsAlreadyAcceptedWork() throws Exception {
        SerialWorker worker = new SerialWorker(2, "serial-worker-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        worker.start();
        worker.offer(() -> {
            firstStarted.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        worker.offer(secondDone::countDown);

        Thread closer = new Thread(worker::close);
        closer.start();

        assertFalse(secondDone.await(50, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();

        closer.join(1000);
        assertFalse(closer.isAlive());
        assertTrue(secondDone.await(1, TimeUnit.SECONDS));
        assertEquals(SerialWorker.State.STOPPED, worker.state());
    }

    @Test
    public void ordinaryTaskFailureDoesNotStopLaterWork() throws Exception {
        SerialWorker worker = new SerialWorker(2, "serial-worker-test");
        worker.start();
        try {
            SerialWorker.SubmitResult<Integer> failing = worker.submit(() -> {
                throw new IllegalStateException("expected");
            });
            SerialWorker.SubmitResult<Integer> next = worker.submit(() -> 42);

            try {
                failing.futureResult().get(1, TimeUnit.SECONDS);
                fail("expected ExecutionException");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }

            assertEquals(Integer.valueOf(42), next.futureResult().get(1, TimeUnit.SECONDS));
            assertEquals(SerialWorker.State.RUNNING, worker.state());
        } finally {
            worker.close();
        }
    }

    @Test
    public void recordsLowAllocationRuntimeCountersAndDurations() throws Exception {
        SerialWorker worker = new SerialWorker(1, "serial-worker-metrics-test");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch queuedDone = new CountDownLatch(1);

        assertEquals(
                SerialWorker.AdmissionResult.NOT_RUNNING,
                worker.offer(() -> { }));

        worker.start();
        try {
            worker.offer(() -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            assertEquals(
                    SerialWorker.AdmissionResult.ACCEPTED,
                    worker.offer(queuedDone::countDown));
            assertEquals(
                    SerialWorker.AdmissionResult.FULL,
                    worker.offer(() -> { }));

            Thread.sleep(10L);
            releaseFirst.countDown();
            assertTrue(queuedDone.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            worker.close();
        }

        assertEquals(2L, worker.acceptedCount());
        assertEquals(1L, worker.fullCount());
        assertEquals(1L, worker.notRunningCount());
        assertEquals(2L, worker.completedCount());
        assertTrue(worker.highWaterMark() >= 1);
        assertTrue(worker.maxQueueWaitNanos() > 0L);
        assertTrue(worker.totalQueueWaitNanos() >= worker.maxQueueWaitNanos());
        assertTrue(worker.maxExecutionNanos() > 0L);
        assertTrue(worker.totalExecutionNanos() >= worker.maxExecutionNanos());
        assertTrue(worker.threadCpuTimeNanos() >= -1L);
    }

    @Test
    public void fatalErrorFaultsWorkerAndCancelsQueuedWork() throws Exception {
        SerialWorker worker = new SerialWorker(2, "serial-worker-test");
        CountDownLatch fatalStarted = new CountDownLatch(1);
        CountDownLatch releaseFatal = new CountDownLatch(1);
        worker.start();

        SerialWorker.SubmitResult<Void> fatal = worker.submit(() -> {
            fatalStarted.countDown();
            releaseFatal.await();
            throw new AssertionError("fatal");
        });
        assertTrue(fatalStarted.await(1, TimeUnit.SECONDS));
        SerialWorker.SubmitResult<Integer> queued = worker.submit(() -> 42);
        releaseFatal.countDown();

        try {
            fatal.futureResult().get(1, TimeUnit.SECONDS);
            fail("expected ExecutionException");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof AssertionError);
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (worker.state() != SerialWorker.State.FAILED && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(SerialWorker.State.FAILED, worker.state());
        assertTrue(worker.failure() instanceof AssertionError);

        try {
            queued.futureResult().get();
            fail("expected queued work to be cancelled");
        } catch (CancellationException expected) {
            // Expected after worker fault.
        }
    }
}
