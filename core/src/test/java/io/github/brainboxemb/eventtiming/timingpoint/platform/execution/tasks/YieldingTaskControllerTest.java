package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.TaskSerialLane;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class YieldingTaskControllerTest {

    @Test
    public void wakeWhileRunningCoalescesIntoOneFollowUpRun()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newSingleThreadScheduledExecutor();
        TaskSerialLane lane =
                new TaskSerialLane(
                        8,
                        "controller-coalesce",
                        worker);
        lane.start();

        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);

        YieldingTaskController controller =
                new YieldingTaskController(
                        lane,
                        () -> {
                            int run = runs.incrementAndGet();
                            if (run == 1) {
                                firstEntered.countDown();
                                try {
                                    releaseFirst.await();
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(
                                            "test interrupted",
                                            ex);
                                }
                            } else {
                                secondRan.countDown();
                            }
                            return TaskStep.done();
                        },
                        failure -> {
                            throw new AssertionError(failure);
                        });

        try {
            controller.wake();
            assertTrue(
                    firstEntered.await(
                            1L,
                            TimeUnit.SECONDS));

            controller.wake();
            controller.wake();

            assertEquals(1, runs.get());
            assertTrue(controller.isRunning());
            assertTrue(controller.wakePending());

            releaseFirst.countDown();

            assertTrue(
                    secondRan.await(
                            1L,
                            TimeUnit.SECONDS));
            await(
                    () -> !controller.isRunning(),
                    1000L);

            assertEquals(2, runs.get());
            assertFalse(controller.wakePending());
        } finally {
            releaseFirst.countDown();
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void executionFailureIsReportedWithoutRestart()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newSingleThreadScheduledExecutor();
        TaskSerialLane lane =
                new TaskSerialLane(
                        8,
                        "controller-failure",
                        worker);
        lane.start();

        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Throwable> observed =
                new AtomicReference<Throwable>();
        CountDownLatch failureObserved =
                new CountDownLatch(1);
        RuntimeException failure =
                new RuntimeException("boom");

        YieldingTaskController controller =
                new YieldingTaskController(
                        lane,
                        () -> {
                            runs.incrementAndGet();
                            throw failure;
                        },
                        observedFailure -> {
                            observed.set(observedFailure);
                            failureObserved.countDown();
                        });

        try {
            controller.wake();
            controller.wake();

            assertTrue(
                    failureObserved.await(
                            1L,
                            TimeUnit.SECONDS));
            assertSame(failure, observed.get());
            assertEquals(1, runs.get());
            assertFalse(controller.isRunning());
        } finally {
            lane.close();
            worker.shutdownNow();
        }
    }

    private static void await(
            java.util.function.BooleanSupplier condition,
            long timeoutMillis)
            throws Exception {
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(
                        "condition did not become true before timeout");
            }
            Thread.sleep(2L);
        }
    }
}
