package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SerialScheduledExecutorTest {

    @Test
    public void immediateAndPeriodicWorkUseTheSameSerialThread()
            throws Exception {
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("serial-scheduled-test");
        AtomicReference<String> immediateThread = new AtomicReference<>();
        AtomicReference<String> periodicThread = new AtomicReference<>();
        CountDownLatch immediateDone = new CountDownLatch(1);
        CountDownLatch periodicDone = new CountDownLatch(1);

        executor.start();
        SerialScheduledExecutor.ScheduledTask periodic = null;
        try {
            assertTrue(executor.execute(() -> {
                immediateThread.set(Thread.currentThread().getName());
                immediateDone.countDown();
            }));
            periodic = executor.scheduleWithFixedDelay(() -> {
                periodicThread.set(Thread.currentThread().getName());
                periodicDone.countDown();
            }, TimeUnit.MILLISECONDS.toNanos(5));

            assertTrue(immediateDone.await(1, TimeUnit.SECONDS));
            assertTrue(periodicDone.await(1, TimeUnit.SECONDS));
            assertEquals("serial-scheduled-test", immediateThread.get());
            assertEquals(immediateThread.get(), periodicThread.get());
        } finally {
            if (periodic != null) {
                periodic.close();
            }
            executor.close();
        }
    }

    @Test
    public void periodicRuntimeFailureDoesNotSuppressLaterRuns()
            throws Exception {
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("serial-scheduled-test");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch secondCall = new CountDownLatch(1);

        executor.start();
        SerialScheduledExecutor.ScheduledTask periodic =
                executor.scheduleWithFixedDelay(() -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("expected");
                    }
                    secondCall.countDown();
                }, TimeUnit.MILLISECONDS.toNanos(5));
        try {
            assertTrue(secondCall.await(1, TimeUnit.SECONDS));

            /*
             * The latch is released inside the periodic task. Queue one immediate
             * barrier behind that task so its wrapper/finally metrics are visible
             * before taking the snapshot.
             */
            CountDownLatch afterSecondRun = new CountDownLatch(1);
            assertTrue(executor.execute(afterSecondRun::countDown));
            assertTrue(afterSecondRun.await(1, TimeUnit.SECONDS));

            SerialScheduledExecutor.Metrics.Snapshot metrics =
                    executor.metrics().snapshot();
            assertEquals(1L, metrics.runtimeFailureCount());
            assertTrue(metrics.periodicExecutionCount() >= 2L);
        } finally {
            periodic.close();
            executor.close();
        }
    }

    @Test
    public void recordsLaneMetricsThroughImmutableSnapshot()
            throws Exception {
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("serial-scheduled-metrics-test");
        CountDownLatch immediateDone = new CountDownLatch(1);
        CountDownLatch periodicDone = new CountDownLatch(1);

        assertFalse(executor.execute(() -> { }));

        executor.start();
        SerialScheduledExecutor.ScheduledTask periodic =
                executor.scheduleWithFixedDelay(
                        periodicDone::countDown,
                        TimeUnit.MILLISECONDS.toNanos(5));
        try {
            assertTrue(executor.execute(immediateDone::countDown));
            assertTrue(immediateDone.await(1, TimeUnit.SECONDS));
            assertTrue(periodicDone.await(1, TimeUnit.SECONDS));

            periodic.close();

            SerialScheduledExecutor.Metrics.Snapshot metrics =
                    executor.metrics().snapshot();
            assertEquals(1L, metrics.immediateAcceptedCount());
            assertEquals(1L, metrics.immediateRejectedCount());
            assertEquals(1L, metrics.scheduledRegistrationCount());
            assertEquals(1L, metrics.scheduledCancellationCount());
            assertEquals(1L, metrics.immediateExecutionCount());
            assertTrue(metrics.periodicExecutionCount() >= 1L);
            assertEquals(0L, metrics.runtimeFailureCount());
            assertTrue(metrics.queueDepth() >= 0);
            assertTrue(metrics.workerThreadCpuTimeNanos() >= -1L);
        } finally {
            periodic.close();
            executor.close();
        }
    }

    @Test
    public void closeStopsNewImmediateWork() {
        SerialScheduledExecutor executor =
                new SerialScheduledExecutor("serial-scheduled-test");
        executor.start();
        executor.close();

        assertEquals(
                SerialScheduledExecutor.State.STOPPED,
                executor.state());
        assertFalse(executor.execute(() -> { }));
    }
}
