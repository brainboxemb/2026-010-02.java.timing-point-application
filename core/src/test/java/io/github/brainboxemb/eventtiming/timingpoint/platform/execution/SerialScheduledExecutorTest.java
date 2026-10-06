package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SerialScheduledExecutorTest {
    private final List<ScheduledThreadPoolExecutor> ownedWorkers =
            new ArrayList<ScheduledThreadPoolExecutor>();

    @After
    public void stopOwnedWorkers() {
        for (ScheduledThreadPoolExecutor worker : ownedWorkers) {
            worker.shutdownNow();
        }
    }

    private SerialScheduledExecutor newLane(String threadName) {
        ScheduledThreadPoolExecutor worker =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> new Thread(
                                runnable,
                                threadName));
        worker.setRemoveOnCancelPolicy(true);
        ownedWorkers.add(worker);
        return new SerialScheduledExecutor(
                4,
                threadName,
                worker);
    }


    @Test
    public void resultBearingWorkUsesTheSameSerialLane()
            throws Exception {
        SerialScheduledExecutor executor =
                newLane("serial-scheduled-submit-test");

        assertEquals(
                SerialExecutor.AdmissionResult.NOT_RUNNING,
                executor.submit(() -> "not-run")
                        .admission());

        executor.start();
        try {
            SerialExecutor.SubmitResult<String> result =
                    executor.submit(
                            () -> Thread.currentThread()
                                    .getName());

            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    result.admission());
            assertEquals(
                    "serial-scheduled-submit-test",
                    result.futureResult().get(
                            1,
                            TimeUnit.SECONDS));
        } finally {
            executor.close();
        }
    }

    @Test
    public void delayedWorkRunsOnceOnTheSameSerialThread()
            throws Exception {
        SerialScheduledExecutor executor =
                newLane("serial-scheduled-delay-test");
        AtomicReference<String> delayedThread =
                new AtomicReference<String>();
        AtomicInteger calls =
                new AtomicInteger();
        CountDownLatch done =
                new CountDownLatch(1);

        executor.start();
        try {
            executor.schedule(() -> {
                delayedThread.set(
                        Thread.currentThread().getName());
                calls.incrementAndGet();
                done.countDown();
            }, TimeUnit.MILLISECONDS.toNanos(5));

            assertTrue(
                    done.await(
                            1,
                            TimeUnit.SECONDS));

            CountDownLatch barrier =
                    new CountDownLatch(1);
            assertTrue(
                    executor.execute(
                            barrier::countDown));
            assertTrue(
                    barrier.await(
                            1,
                            TimeUnit.SECONDS));

            assertEquals(
                    "serial-scheduled-delay-test",
                    delayedThread.get());
            assertEquals(
                    1,
                    calls.get());

            SerialScheduledExecutorMetrics.Snapshot metrics =
                    executor.metrics().snapshot();
            assertEquals(
                    1L,
                    metrics.delayedExecutionCount());
        } finally {
            executor.close();
        }
    }

    @Test
    public void immediateAndPeriodicWorkUseTheSameSerialThread()
            throws Exception {
        SerialScheduledExecutor executor =
                newLane("serial-scheduled-test");
        AtomicReference<String> immediateThread = new AtomicReference<>();
        AtomicReference<String> periodicThread = new AtomicReference<>();
        CountDownLatch immediateDone = new CountDownLatch(1);
        CountDownLatch periodicDone = new CountDownLatch(1);

        executor.start();
        SerialScheduledExecutor.ScheduledRegistration periodic = null;
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
                newLane("serial-scheduled-test");
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch secondCall = new CountDownLatch(1);

        executor.start();
        SerialScheduledExecutor.ScheduledRegistration periodic =
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

            SerialScheduledExecutorMetrics.Snapshot metrics =
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
                newLane("serial-scheduled-metrics-test");
        CountDownLatch immediateDone = new CountDownLatch(1);
        CountDownLatch periodicDone = new CountDownLatch(1);

        assertFalse(executor.execute(() -> { }));

        executor.start();
        SerialScheduledExecutor.ScheduledRegistration periodic =
                executor.scheduleWithFixedDelay(
                        periodicDone::countDown,
                        TimeUnit.MILLISECONDS.toNanos(5));
        try {
            assertTrue(periodicDone.await(1, TimeUnit.SECONDS));

            /*
             * Run one normal lane task after the periodic callback. Serial lane
             * ordering guarantees the periodic wrapper's finally block (which
             * records periodicExecutionCount) completed before this task runs.
             */
            assertTrue(executor.execute(immediateDone::countDown));
            assertTrue(immediateDone.await(1, TimeUnit.SECONDS));

            periodic.close();

            SerialScheduledExecutorMetrics.Snapshot metrics =
                    executor.metrics().snapshot();
            assertEquals(1L, metrics.immediateAcceptedCount());
            assertEquals(1L, metrics.immediateRejectedCount());
            assertEquals(1L, metrics.scheduledRegistrationCount());
            assertEquals(1L, metrics.scheduledCancellationCount());
            assertEquals(1L, metrics.immediateExecutionCount());
            assertEquals(0L, metrics.delayedExecutionCount());
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
    public void sharedScheduledLanesUseOneRoleWorkerAndCloseIndependently()
            throws Exception {
        ScheduledThreadPoolExecutor shared =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> new Thread(
                                runnable,
                                "shared-tag-worker"));
        shared.setRemoveOnCancelPolicy(true);

        SerialScheduledExecutor first =
                new SerialScheduledExecutor(
                        4,
                        "tag-A",
                        shared);
        SerialScheduledExecutor second =
                new SerialScheduledExecutor(
                        4,
                        "tag-B",
                        shared);

        AtomicReference<String> firstThread =
                new AtomicReference<String>();
        AtomicReference<String> secondThread =
                new AtomicReference<String>();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);

        first.start();
        second.start();
        try {
            assertTrue(first.execute(() -> {
                firstThread.set(
                        Thread.currentThread().getName());
                firstDone.countDown();
            }));
            assertTrue(second.execute(() -> {
                secondThread.set(
                        Thread.currentThread().getName());
                secondDone.countDown();
            }));

            assertTrue(
                    firstDone.await(
                            1,
                            TimeUnit.SECONDS));
            assertTrue(
                    secondDone.await(
                            1,
                            TimeUnit.SECONDS));
            assertEquals(
                    "shared-tag-worker",
                    firstThread.get());
            assertEquals(
                    firstThread.get(),
                    secondThread.get());

            first.close();
            assertFalse(shared.isShutdown());

            CountDownLatch siblingStillRuns =
                    new CountDownLatch(1);
            assertTrue(
                    second.execute(
                            siblingStillRuns::countDown));
            assertTrue(
                    siblingStillRuns.await(
                            1,
                            TimeUnit.SECONDS));
        } finally {
            first.close();
            second.close();
            shared.shutdownNow();
        }
    }

    @Test
    public void sharedPeriodicWorkRunsOnTheSharedRoleWorker()
            throws Exception {
        ScheduledThreadPoolExecutor shared =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> new Thread(
                                runnable,
                                "shared-tag-worker"));
        shared.setRemoveOnCancelPolicy(true);
        SerialScheduledExecutor lane =
                new SerialScheduledExecutor(
                        4,
                        "tag-A",
                        shared);
        AtomicReference<String> periodicThread =
                new AtomicReference<String>();
        CountDownLatch periodicDone =
                new CountDownLatch(1);

        lane.start();
        SerialScheduledExecutor.ScheduledRegistration periodic =
                lane.scheduleWithFixedDelay(() -> {
                    periodicThread.set(
                            Thread.currentThread().getName());
                    periodicDone.countDown();
                }, TimeUnit.MILLISECONDS.toNanos(5));
        try {
            assertTrue(
                    periodicDone.await(
                            1,
                            TimeUnit.SECONDS));
            assertEquals(
                    "shared-tag-worker",
                    periodicThread.get());
        } finally {
            periodic.close();
            lane.close();
            shared.shutdownNow();
        }
    }

    @Test
    public void closeStopsNewImmediateWork() {
        SerialScheduledExecutor executor =
                newLane("serial-scheduled-test");
        executor.start();
        executor.close();

        assertEquals(
                SerialScheduledExecutor.State.STOPPED,
                executor.state());
        assertFalse(executor.execute(() -> { }));
    }
}
